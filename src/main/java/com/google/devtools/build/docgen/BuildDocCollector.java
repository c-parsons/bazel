// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.docgen;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.docgen.DocgenConsts.RuleType;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.RuleClass;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Class that parses the documentation fragments of rule-classes and
 * generates the html format documentation.
 */
class BuildDocCollector {
  private ConfiguredRuleClassProvider ruleClassProvider;
  private boolean printMessages;

  public BuildDocCollector(ConfiguredRuleClassProvider ruleClassProvider,
      boolean printMessages) {
    this.ruleClassProvider = ruleClassProvider;
    this.printMessages = printMessages;
  }

  /**
   * Collects all the rule and attribute documentation present in inputDirs, integrates the
   * attribute documentation in the rule documentation and returns the rule documentation.
   */
  public Map<String, RuleDocumentation> collect(String[] inputDirs)
      throws BuildEncyclopediaDocException, IOException {
    // RuleDocumentations are generated in order (based on rule type then alphabetically).
    // The ordering is also used to determine in which rule doc the common attribute docs are
    // generated (they are generated at the first appearance).
    Map<String, RuleDocumentation> ruleDocEntries = new TreeMap<>();
    // RuleDocumentationAttribute objects equal based on attributeName so they have to be
    // collected in a List instead of a Set.
    ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries =
        LinkedListMultimap.create();

    // Map of rule class name to file that defined it.
    Map<String, File> ruleClassFiles = new HashMap<>();

    // Set of files already processed. The same file may be encountered multiple times because
    // directories are processed recursively, and an input directory may be a subdirectory of
    // another one.
    Set<File> processedFiles = new HashSet<>();

    for (String inputDir : inputDirs) {
      if (printMessages) {
        System.out.println(" Processing input directory: " + inputDir);
      }
      int ruleNum = ruleDocEntries.size();
      collectDocs(processedFiles, ruleClassFiles, ruleDocEntries, attributeDocEntries,
          new File(inputDir));
      if (printMessages) {
        System.out.println(" " + (ruleDocEntries.size() - ruleNum)
            + " rule documentations found.");
      }
    }

    processAttributeDocs(ruleDocEntries.values(), attributeDocEntries);
    return ruleDocEntries;
  }

  /**
   * Go through all attributes of all documented rules and search the best attribute documentation
   * if exists. The best documentation is the closest documentation in the ancestor graph. E.g. if
   * java_library.deps documented in $rule and $java_rule then the one in $java_rule is going to
   * apply since it's a closer ancestor of java_library.
   */
  private void processAttributeDocs(Iterable<RuleDocumentation> ruleDocEntries,
      ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries)
          throws BuildEncyclopediaDocException {
    for (RuleDocumentation ruleDoc : ruleDocEntries) {
      RuleClass ruleClass = ruleClassProvider.getRuleClassMap().get(ruleDoc.getRuleName());
      if (ruleClass != null) {
        if (ruleClass.isDocumented()) {
          Class<? extends RuleDefinition> ruleDefinition =
              ruleClassProvider.getRuleClassDefinition(ruleDoc.getRuleName());
          for (Attribute attribute : ruleClass.getAttributes()) {
            String attrName = attribute.getName();
            List<RuleDocumentationAttribute> attributeDocList =
                attributeDocEntries.get(attrName);
            if (attributeDocList != null) {
              // There are attribute docs for this attribute.
              // Search the closest one in the ancestor graph.
              // Note that there can be only one 'closest' attribute since we forbid multiple
              // inheritance of the same attribute in RuleClass.
              int minLevel = Integer.MAX_VALUE;
              RuleDocumentationAttribute bestAttributeDoc = null;
              for (RuleDocumentationAttribute attributeDoc : attributeDocList) {
                int level = attributeDoc.getDefinitionClassAncestryLevel(ruleDefinition);
                if (level >= 0 && level < minLevel) {
                  bestAttributeDoc = attributeDoc;
                  minLevel = level;
                }
              }
              if (bestAttributeDoc != null) {
                ruleDoc.addAttribute(bestAttributeDoc);
              // If there is no matching attribute doc try to add the common.
              } else if (ruleDoc.getRuleType().equals(RuleType.BINARY)
                  && PredefinedAttributes.BINARY_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.BINARY_ATTRIBUTES.get(attrName));
              } else if (ruleDoc.getRuleType().equals(RuleType.TEST)
                  && PredefinedAttributes.TEST_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.TEST_ATTRIBUTES.get(attrName));
              } else if (PredefinedAttributes.COMMON_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.COMMON_ATTRIBUTES.get(attrName));
              }
            }
          }
        }
      } else {
        throw ruleDoc.createException("Can't find RuleClass for " + ruleDoc.getRuleName());
      }
    }
  }

  /**
   * Goes through all the html files and subdirs under inputPath and collects the rule
   * and attribute documentations using the ruleDocEntries and attributeDocEntries variable.
   */
  public void collectDocs(
      Set<File> processedFiles,
      Map<String, File> ruleClassFiles,
      Map<String, RuleDocumentation> ruleDocEntries,
      ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries,
      File inputPath) throws BuildEncyclopediaDocException, IOException {
    if (processedFiles.contains(inputPath)) {
      return;
    }

    if (inputPath.isFile()) {
      if (DocgenConsts.JAVA_SOURCE_FILE_SUFFIX.apply(inputPath.getName())) {
        SourceFileReader sfr = new SourceFileReader(
            ruleClassProvider, inputPath.getAbsolutePath());
        sfr.readDocsFromComments();
        for (RuleDocumentation d : sfr.getRuleDocEntries()) {
          String ruleName = d.getRuleName();
          if (ruleDocEntries.containsKey(ruleName)
              && !ruleClassFiles.get(ruleName).equals(inputPath)) {
            System.err.printf("WARNING: '%s' from '%s' overrides value already in map from '%s'\n",
                d.getRuleName(), inputPath, ruleClassFiles.get(ruleName));
          }
          ruleClassFiles.put(ruleName, inputPath);
          ruleDocEntries.put(ruleName, d);
        }
        if (attributeDocEntries != null) {
          // Collect all attribute documentations from this file.
          attributeDocEntries.putAll(sfr.getAttributeDocEntries());
        }
      }
    } else if (inputPath.isDirectory()) {
      for (File childPath : inputPath.listFiles()) {
        collectDocs(processedFiles, ruleClassFiles, ruleDocEntries, attributeDocEntries, childPath);
      }
    }

    processedFiles.add(inputPath);
  }
}
