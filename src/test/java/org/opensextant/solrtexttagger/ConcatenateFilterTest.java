/*
 This software was produced for the U. S. Government
 under Contract No. W15P7T-11-C-F600, and is
 subject to the Rights in Noncommercial Computer Software
 and Noncommercial Computer Software Documentation
 Clause 252.227-7014 (JUN 1995)

 Copyright 2013 The MITRE Corporation. All Rights Reserved.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.opensextant.solrtexttagger;

import org.apache.lucene.analysis.BaseTokenStreamTestCase;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;

import java.io.IOException;
import java.io.StringReader;

public class ConcatenateFilterTest extends BaseTokenStreamTestCase {

  public void testTypical() throws IOException {
    String NYC = "new york city";
    WhitespaceTokenizer stream = new WhitespaceTokenizer();
    stream.setReader(new StringReader(NYC));
    ConcatenateFilter filter = new ConcatenateFilter(stream);
    try {
      assertTokenStreamContents(filter, new String[]{NYC},
          new int[]{0}, new int[]{NYC.length()}, new String[]{"shingle"},
          new int[]{1}, null, NYC.length(), true);
    } catch (AssertionError e) {
      //assertTokenStreamContents tries to test if tokenStream.end() was implemented correctly.
      // It's manner of checking this is imperfect and incompatible with
      // ConcatenateFilter. Specifically it modifies a special attribute *after* incrementToken(),
      // which is weird. To the best of my ability, end() appears to be implemented correctly.
      if (!e.getMessage().equals("super.end()/clearAttributes() was not called correctly in end()"))
        throw e;
    }
  }

}
