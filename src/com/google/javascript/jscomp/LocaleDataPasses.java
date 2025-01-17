/*
 * Copyright 2021 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.GwtIncompatible;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.NodeTraversal.Callback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Node.SideEffectFlags;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * A compiler pass to collect locale data for substitution at a later stage of the compilation,
 * where the locale data to be collected present in files annotated with `@localeFile`, and the
 * locale extracted for assignments annotated with `@localeObject` and specific values within the
 * structure are annotated with `@localeValue`.
 *
 * <p>The actual locale selection is a switch with a specific structure annotated with
 * `@localeSelect`. This switch is replaced with an assignment to a clone of the first locale with
 * the locale specific values replaced.
 */
@GwtIncompatible("Unnecessary")
final class LocaleDataPasses {

  private LocaleDataPasses() {}

  // Replacements for values that needed to be protected from optimizations.
  static final String GOOG_LOCALE_REPLACEMENT = "__JSC_LOCALE__";
  static final String LOCALE_VALUE_REPLACEMENT = "__JSC_LOCALE_VALUE__";

  public static final DiagnosticType LOCALE_FILE_MALFORMED =
      DiagnosticType.error("JSC_LOCALE_FILE_MALFORMED", "Malformed locale data file. {0}");

  public static final DiagnosticType LOCALE_MISSING_BASE_FILE =
      DiagnosticType.error(
          "JSC_LOCALE_MISSING_BASE_FILE", "Missing base file for extension file: {0}");

  // A simple class used to produce a sequence, by default, starting at zero
  private static class SequentialIntProvider {
    private int value;

    SequentialIntProvider() {
      this(0);
    }

    SequentialIntProvider(int startingValue) {
      value = startingValue;
    }

    int currentValue() {
      return value - 1;
    }

    int inc() {
      return value++;
    }
  }

  static class ExtractAndProtect implements CompilerPass {
    private final AbstractCompiler compiler;
    private ArrayList<LinkedHashMap<String, Node>> valueMaps;

    ExtractAndProtect(AbstractCompiler compiler) {
      this.compiler = compiler;
    }

    @Override
    public void process(Node externs, Node root) {
      ProtectCurrentLocale protectLocaleCallback = new ProtectCurrentLocale(compiler);
      NodeTraversal.traverse(compiler, root, protectLocaleCallback);
      ExtractAndProtectLocaleData localeDataCallback = new ExtractAndProtectLocaleData(compiler);
      NodeTraversal.traverse(compiler, root, localeDataCallback);

      valueMaps = localeDataCallback.getLocaleValuesDataMaps();
    }

    public ArrayList<LinkedHashMap<String, Node>> getLocaleValuesDataMaps() {
      checkNotNull(valueMaps, "process must be called before getLocaleValuesDataMaps");
      return valueMaps;
    }
  }

  /**
   * This pass does several things:
   *
   * <p>1. finds an example locale object and creates a generic template object
   *
   * <p>2. rewrites the locale specific assignment with a generic template
   *
   * <p>3. locale objects and extracts the tagged locale specific values so that they can be use in
   * the later phase to replace the template values
   */
  private static class ExtractAndProtectLocaleData implements Callback {
    private final AbstractCompiler compiler;
    // There is a single sequence that spans all files.
    private final SequentialIntProvider intProvider = new SequentialIntProvider();
    // A LocalizedDataGroup is a represents the contexts of a single file,
    // where each file only represents a single object for all locales
    private LocalizedDataGroup datagroup = null;

    // There are two elements in identify a locale value
    // - the file ('ext' files are pooled with their partners)
    // - the location in the data structure
    // Each file is only allowed to create a single value group
    //
    // Each "file" represents a range of value ids, so that the entire set of values
    // across all locale data groups is represented in a single flat array.
    //
    // Additionally the assignment target of the locale object is
    // used to extract the locale target

    private final LinkedHashMap<String, LocalizedDataGroup> fileToDataGroup = new LinkedHashMap<>();

    // For each entry in the array we keep a map of locales to values
    private final ArrayList<LinkedHashMap<String, Node>> valueMaps = new ArrayList<>();

    ExtractAndProtectLocaleData(AbstractCompiler compiler) {
      this.compiler = compiler;
    }

    /** Returns the data structure used by "LocaleSubstitutions". */
    public ArrayList<LinkedHashMap<String, Node>> getLocaleValuesDataMaps() {
      return valueMaps;
    }

    public void process(Node externs, Node root) {
      // Create the extern symbol
      NodeUtil.createSynthesizedExternsSymbol(compiler, LOCALE_VALUE_REPLACEMENT);
      NodeTraversal.traverse(compiler, root, this);
    }

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      switch (n.getToken()) {
        case SCRIPT:
          if (!isLocaleFileNode(n)) {
            return false;
          }

          checkState(datagroup == null);
          datagroup = getDataGroupForFile(n);
          if (datagroup == null) {
            // Don't visit the file if an error is reported.
            return false;
          }
          break;

        case NAME:
          // assignment to file local value:
          //   let thing_en = ...
          if (n.hasOneChild()) {
            // JSDoc is located on the parent node (LET, CONST, VAR)
            if (isLocaleObjectNode(parent)) {
              Node target = n;
              Node value = n.getFirstChild();
              handleLocaleObjectAssignment(target, value);
            }
          }
          break;

        case EXPR_RESULT:
          // assignment to value:
          //   thing_en = ...
          Node expr = n.getFirstChild();
          if (expr.isAssign()) {
            if (isLocaleObjectNode(expr)) {
              // extract assign value
              Node assign = n.getFirstChild();
              Node value = assign.getLastChild();
              Node target = assign.getFirstChild();
              handleLocaleObjectAssignment(target, value);
            }
          }
          break;

        default: // fall out
      }

      return true;
    }

    private void handleLocaleObjectAssignment(Node target, Node value) {
      checkNotNull(datagroup);

      if (!value.isObjectLit()) {
        compiler.report(JSError.make(value, LOCALE_FILE_MALFORMED, "Object literal expected"));
        return;
      }

      // Use the first "localeObject" as a template to use.
      if (this.datagroup.templatedStructure == null) {
        checkState(value.isObjectLit());
        storeTemplatedClone(value);
      }
      checkNotNull(datagroup.templatedStructure);

      // The specific locale for which to record values for
      String localeId = determineLocaleIdFromAssignmentTarget(target);
      if (localeId.equals("en")) {
        if (datagroup.seenDefaultLocale) {
          compiler.report(
              JSError.make(
                  target, LOCALE_FILE_MALFORMED, "Duplicate locale definition " + localeId));
        }
        datagroup.seenDefaultLocale = true;
      }
      extractAndValidate(localeId, this.datagroup.templatedStructure, value);
    }

    /**
     * Extract the locale id from the assignment target. The locale id is assumed to be everything
     * after the first "_" is the name.
     */
    private String determineLocaleIdFromAssignmentTarget(Node target) {
      String rawName = target.getQualifiedName();
      // The locale name is everything after the first "_";
      String locale = rawName.substring(rawName.indexOf('_') + 1);
      checkNotNull(locale);

      // Make some attempt to validate the locale name. This isn't every effective though and
      // the real validation is done elsewhere by ensuring that "en" (the default locale) is found.
      if (!locale.matches("[a-z]+(_[a-zA-Z]+(_[a-zA-Z0-9]+)?)?")) {
        compiler.report(
            JSError.make(target, LOCALE_FILE_MALFORMED, "Unexpected locale id: " + locale));
      }

      return locale;
    }

    /**
     * Walk the ASTs of the template object and locale object, extracting the values, reports an
     * error if the two trees are not in sync or if the locale specific value is not a supported
     * value.
     */
    private void extractAndValidate(String localeId, Node templateNode, Node localeNode) {
      checkState(templateNode.isObjectLit());
      checkState(localeNode.isObjectLit());
      SequentialIntProvider seq = new SequentialIntProvider(this.datagroup.firstValueId);
      extractAndValidate(localeId, seq, templateNode, localeNode);
    }

    private void extractAndValidate(
        String localeId, SequentialIntProvider seq, Node templateNode, Node localeNode) {
      if (isTemplateLocaleValueNode(templateNode)) {

        if (!isLocaleValueNode(localeNode)) {
          compiler.report(JSError.make(localeNode, LOCALE_FILE_MALFORMED, "Expected @localeValue"));
          return;
        }

        int valueNumber = seq.inc();

        // NOTE consider validating template sequence number matches the "valueNumber" here
        LinkedHashMap<String, Node> map = valueMaps.get(valueNumber);

        validateLocaleValue(localeNode);

        // It is necessary to clone the nodes as we leave the existing values in place for
        // the applications that directly reference the per-locale values.
        Node value = map.put(localeId, localeNode.cloneTree());

        if (value != null) {
          compiler.report(
              JSError.make(
                  localeNode, LOCALE_FILE_MALFORMED, "Duplicate locale definition: " + localeId));
        }

        // No need to visit children nodes.
      } else {

        if (templateNode.getToken() != localeNode.getToken()) {
          compiler.report(
              JSError.make(
                  localeNode, LOCALE_FILE_MALFORMED, "Expected " + templateNode.getToken()));
          return;
        }

        if (templateNode.getChildCount() != localeNode.getChildCount()) {
          compiler.report(
              JSError.make(localeNode, LOCALE_FILE_MALFORMED, "Missing or unexpected expressions"));
          return;
        }

        if (isLocaleValueNode(localeNode)) {
          compiler.report(
              JSError.make(
                  localeNode,
                  LOCALE_FILE_MALFORMED,
                  "Mismatch between locales: unexpected @localeValue"));
          return;
        }

        // Walk the two trees in parallel
        Node templateChild = templateNode.getFirstChild();
        Node localeChild = localeNode.getFirstChild();
        for (; templateChild != null; ) {
          extractAndValidate(localeId, seq, templateChild, checkNotNull(localeChild));
          templateChild = templateChild.getNext();
          localeChild = localeChild.getNext();
        }
      }
    }

    /**
     * Validate that the locale specific values are primitives or an array of primitives. NOTE: This
     * may be too restrictive and should be expanded as necessary but care should be taken not to
     * include values that would be influenced by the optimizations (variable and property
     * references, functions, etc).
     */
    private void validateLocaleValue(Node valueNode) {
      switch (valueNode.getToken()) {
        case TRUE:
        case FALSE:
        case NUMBER:
        case STRINGLIT:
          break;
        case ARRAYLIT:
          for (Node c = valueNode.getFirstChild(); c != null; c = c.getNext()) {
            validateLocaleValue(c);
          }
          break;
        default:
          compiler.report(
              JSError.make(
                  valueNode,
                  LOCALE_FILE_MALFORMED,
                  "Unexpected expression. "
                      + "Only boolean, number, or string values or array literals"
                      + " of the same are allowed"));
          break;
      }
    }

    private boolean isLocaleFileNode(Node n) {
      JSDocInfo info = n.getJSDocInfo();
      return info != null && info.isLocaleFile();
    }

    private boolean isLocaleObjectNode(Node n) {
      JSDocInfo info = n.getJSDocInfo();
      return info != null && info.isLocaleObject();
    }

    private boolean isLocaleSelectNode(Node n) {
      JSDocInfo info = n.getJSDocInfo();
      return info != null && info.isLocaleSelect();
    }

    private boolean isLocaleValueNode(Node n) {
      JSDocInfo info = n.getJSDocInfo();
      return info != null && info.isLocaleValue();
    }

    private final Node qnameForLocaleValue = IR.name(LOCALE_VALUE_REPLACEMENT);

    private boolean isTemplateLocaleValueNode(Node n) {
      return n.isCall() && n.getFirstChild().matchesName(qnameForLocaleValue);
    }

    // Represents state for a given file.  Each file represents a single
    // data structure with a range of values.
    private static class LocalizedDataGroup {
      public boolean seenSelect = false;
      public boolean seenDefaultLocale = false;
      int firstValueId = -1;
      int lastValueId = -1; // inclusive for validation
      Node templatedStructure;
    }

    /**
     * Locale definitions can be spread across multiple files. Specifically, Closure Library's
     * "...ext.js" files are paired with with a file that provides definitions for the more commonly
     * supported locales.
     *
     * <p>To support this we need to store LocalizedDataGroup object used for the main file to
     * pickup where it left off.
     */
    private LocalizedDataGroup getDataGroupForFile(Node script) {
      String filepath = script.getSourceFileName();

      // TODO(b/188087270): remove the "ext" special case
      if (filepath.endsWith("ext.js")) {
        // Allow "ext"
        String basefilepath = filepath.replace("ext.js", ".js");

        LocalizedDataGroup datagroup = fileToDataGroup.get(basefilepath);

        if (datagroup == null) {
          compiler.report(JSError.make(script, LOCALE_MISSING_BASE_FILE, basefilepath));
        }

        // Allow a secondary switch to exist in the "ext.js" file.
        // TODO(user): Try to remove this.
        datagroup.seenSelect = false;

        return datagroup;
      }

      LocalizedDataGroup dataGroup = new LocalizedDataGroup();
      dataGroup.firstValueId = intProvider.currentValue() + 1;
      fileToDataGroup.put(filepath, dataGroup);
      return dataGroup;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isSwitch()) {
        if (isLocaleSelectNode(n)) {
          if (datagroup.seenSelect) {
            compiler.report(JSError.make(n, LOCALE_FILE_MALFORMED, "Duplicate switch"));
            return;
          }

          datagroup.seenSelect = true;

          if (datagroup.templatedStructure != null) { // this is a reported error, don't crash
            // replace the switch expression with the templated default
            replaceSwitchWithTemplate(n);
          }
        }
      }

      if (n.isScript()) {
        validateAndReset(n);
      }
    }

    /**
     * Replace the switch expression with a direct assignment to the templated expression. This will
     * allow the compiler to optimize the structure of the data, without modifying the values so
     * that they can be replaced after all the optimizations have completed.
     */
    private void replaceSwitchWithTemplate(Node n) {
      checkState(n.isSwitch(), n);
      Node target = extractAssignmentTargetFromSwitch(n);
      if (target == null) {
        // an error diagnostic has been reported bail out.
        return;
      }

      Node replacement =
          IR.exprResult(IR.assign(target, this.datagroup.templatedStructure))
              .srcrefTreeIfMissing(n);
      n.replaceWith(replacement);
      compiler.reportChangeToEnclosingScope(replacement);
    }

    /**
     * Inspect the first case of the switch and determine the assignment target to use. This rigidly
     * defines the shape the switch statement must take.
     */
    private Node extractAssignmentTargetFromSwitch(Node switchNode) {
      // For reference, the AST structure of a switch statement is
      // SWITCH
      //   EXPR
      //   CASE|DEFAULT_CASE
      //     BLOCK
      //       ... statements ...
      //   ...
      //

      checkState(switchNode.isSwitch());
      Node caseNode = switchNode.getSecondChild();
      checkState(caseNode.isCase());

      // Skip fall-thru cases (cases whose blocks are empty)
      while (!caseNode.getLastChild().hasChildren()) {
        caseNode = caseNode.getNext();
      }

      Node caseBody = caseNode.getLastChild();
      checkState(caseBody.isBlock());
      if (!caseBody.getLastChild().isBreak()) {
        compiler.report(JSError.make(caseBody, LOCALE_FILE_MALFORMED, "Missing break"));
        return null;
      }

      if (!caseBody.hasTwoChildren()) {
        compiler.report(JSError.make(caseBody, LOCALE_FILE_MALFORMED, "Unexpected statements"));
        return null;
      }

      if (!NodeUtil.isExprAssign(caseBody.getFirstChild())) {
        compiler.report(JSError.make(caseBody, LOCALE_FILE_MALFORMED, "Missing assignment"));
        return null;
      }

      // extract the assignment target
      Node expr = caseBody.getFirstChild();
      Node assign = expr.getFirstChild();
      Node target = assign.getFirstChild();

      if (!target.isQualifiedName()) {
        compiler.report(
            JSError.make(target, LOCALE_FILE_MALFORMED, "Unexpected assignment target"));
        return null;
      }

      return target.cloneTree();
    }

    /**
     * Validation and cleanup performed when leaving a SCRIPT node. This is a last chance to check
     * that the file had the expected shape.
     */
    private void validateAndReset(Node script) {
      checkNotNull(datagroup); // shouldn't get here if this is null

      if (!datagroup.seenDefaultLocale) {
        compiler.report(
            JSError.make(script, LOCALE_FILE_MALFORMED, "Missing default locale definition 'en'"));
      }

      if (!datagroup.seenSelect) {
        compiler.report(
            JSError.make(script, LOCALE_FILE_MALFORMED, "Missing or misplaced @localeSelect"));
      }

      // If this there is a localeSelect, and the template structure isn't provided, another error
      // will have been reported.
      checkNotNull(datagroup.templatedStructure);
      datagroup = null;
    }

    /**
     * Store a clone of the provided Node. The child nodes in the tree that are annotated
     * with @localeValue are replaced with __JSC_LOCALE_VALUE__ calls. This new AST snippet will be
     * used as part of the replacement of the @localeSelect annotated switch in the file.
     */
    private void storeTemplatedClone(Node n) {
      checkState(datagroup.firstValueId == intProvider.currentValue() + 1);
      checkState(datagroup.lastValueId == -1);
      checkState(datagroup.templatedStructure == null);

      datagroup.templatedStructure = replaceValueNodesInClone(n.cloneTree());

      datagroup.lastValueId = intProvider.currentValue();

      // At least one value is expected, otherwise the input source is malformed
      checkState(datagroup.firstValueId <= intProvider.currentValue());
    }

    private Node replaceValueNodesInClone(Node n) {
      replaceValueNodesInClone(n, intProvider);
      return n;
    }

    private void replaceValueNodesInClone(Node n, SequentialIntProvider valueNumberProvider) {
      JSDocInfo info = n.getJSDocInfo();
      if (info != null && info.isLocaleValue()) {
        int valueNumber = valueNumberProvider.inc();
        // Populate the value maps to store locale specific values.
        valueMaps.add(new LinkedHashMap<>());
        checkState(valueMaps.size() - 1 == valueNumber);
        Node replacement =
            NodeUtil.newCallNode(IR.name(LOCALE_VALUE_REPLACEMENT), IR.number(valueNumber))
                .srcrefTree(n);

        replacement.setSideEffectFlags(SideEffectFlags.NO_SIDE_EFFECTS);
        n.replaceWith(replacement);
        // NOTE: no call to compiler.reportChangeToEnclosingScope because this is a detached clone
        // NOTE: @localeValue within @localeValue isn't supported so don't bother looking at
        // children.  Any validation will be done when extracting values for each locale.
        return;
      }

      Node next = null;
      for (Node c = n.getFirstChild(); c != null; c = next) {
        next = c.getNext();

        replaceValueNodesInClone(c, valueNumberProvider);
      }
    }
  }

  /**
   * To avoid the optimizations from optimizing known constants, make then non-const by replacing
   * them with a call to an extern value.
   */
  private static class ProtectCurrentLocale implements Callback {
    private final Node qnameForGoogLocale = IR.getprop(IR.name("goog"), "LOCALE");

    private final AbstractCompiler compiler;
    private boolean replaced = false;

    ProtectCurrentLocale(AbstractCompiler compiler) {
      this.compiler = compiler;
    }

    public void process(Node externs, Node root) {
      // Create the extern symbol
      NodeUtil.createSynthesizedExternsSymbol(compiler, GOOG_LOCALE_REPLACEMENT);
      NodeTraversal.traverse(compiler, root, this);
    }

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      return !replaced;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (parent != null
          && parent.isAssign()
          && parent.getFirstChild() == n
          && n.matchesQualifiedName(qnameForGoogLocale)) {

        Node value = parent.getLastChild();
        value.replaceWith(IR.name(GOOG_LOCALE_REPLACEMENT));
        compiler.reportChangeToEnclosingScope(parent);

        // Mark the value as found.  The definition should be early in the traversal so
        // we can avoid most of the work by terminating early.
        replaced = true;
      }
    }
  }

  /**
   * This class performs the actual locale specific substitutions for the stand-in values create by
   * `ExtractAndProtectLocaleData` It will replace both __JSC_LOCALE__ and __JSC_LOCALE_VALUE__
   * references.
   */
  static class LocaleSubstitutions extends AbstractPostOrderCallback implements CompilerPass {

    private static final String DEFAULT_LOCALE = "en";
    private final Node qnameForLocale = IR.name(GOOG_LOCALE_REPLACEMENT);
    private final Node qnameForLocaleValue = IR.name(LOCALE_VALUE_REPLACEMENT);

    private final AbstractCompiler compiler;
    private final ArrayList<LinkedHashMap<String, Node>> localeValueMap;
    private final String locale;

    LocaleSubstitutions(
        AbstractCompiler compiler,
        String locale,
        ArrayList<LinkedHashMap<String, Node>> localeValueMap) {
      this.compiler = compiler;
      this.locale = normalizeLocale(checkNotNull(locale));
      this.localeValueMap = checkNotNull(localeValueMap);
    }

    private String normalizeLocale(String locale) {
      return locale.replace('-', '_');
    }

    public void process(Node externs, Node root) {
      // Create the extern symbol
      NodeTraversal.traverse(compiler, root, this);
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall() && n.getFirstChild().matchesName(qnameForLocaleValue)) {
        Node value = n.getSecondChild();
        int valueIndex = (int) value.getDouble();
        Node replacement = lookupValueWithFallback(valueIndex).cloneTree();
        n.replaceWith(replacement);
        compiler.reportChangeToEnclosingScope(replacement);
      } else if (n.matchesName(this.qnameForLocale)) {
        Node replacement = IR.string(locale).srcref(n);
        n.replaceWith(replacement);
        compiler.reportChangeToEnclosingScope(replacement);
      }
    }

    private Node lookupValueWithFallback(int valueIndex) {
      Node value = lookupValue(locale, valueIndex);
      if (value == null) {
        value = lookupValue(DEFAULT_LOCALE, valueIndex);
      }
      return checkNotNull(value);
    }

    private Node lookupValue(String locale, int valueIndex) {
      return localeValueMap.get(valueIndex).get(locale);
    }
  }
}
