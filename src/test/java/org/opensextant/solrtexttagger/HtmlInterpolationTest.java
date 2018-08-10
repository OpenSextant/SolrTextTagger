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

import org.junit.Ignore;
import org.junit.Test;

public class HtmlInterpolationTest extends XmlInterpolationTest {
  @Override
  public void setUp() throws Exception {
    super.setUp();
    baseParams.set("htmlOffsetAdjust", "true");
  }

  @Override
  @Test @Ignore //because in html mode, seemingly everything is valid
  public void testValidatingXml() throws Exception {
  }

  @Override
  @Test @Ignore //because in html mode, seemingly everything is valid
  public void testInvalidXml() throws Exception {
  }

  @Override
  protected void validateXml(String xml) throws Exception {
    //cause this test to *not* try to parse as actual html
  }

  @Test
  public void testHtml() throws Exception {
    buildNames("start end");

    assertXmlTag("<doc>before start <br> end after</doc>", true);//br is assumed empty

    //no wrapping tags:
    assertXmlTag("start end", true);
    assertXmlTag("start end <em>other text</em>", true);
    assertXmlTag("start end<em> other text</em>", true);
    assertXmlTag("<em>other text</em> start end", true);
  }
  
  @Test
  public void testShortHtml() throws Exception{
	  buildNames("start","end");
	  assertXmlTag("start <td/> end", 2);
	  buildNames("start end");
	  assertXmlTag("start <td/> end", true);
  }

  @Test
  public void testHtmlNonTaggable() throws Exception {
    baseParams.set("nonTaggableTags","a" + (random().nextBoolean() ? ",sub" : ""));
    buildNames("start end");

    assertXmlTag("start end", true);
    assertXmlTag("start <a>end</a>", false);
    assertXmlTag("<a>start</a> end", false);
    assertXmlTag("<doc><a>before </a>start <br> end<a> after</a></doc>", true);//adjacent
    assertXmlTag("<doc><a>before <a>inner</a> </a>start <br> end<a> after</a></doc>", true);

  }
}
