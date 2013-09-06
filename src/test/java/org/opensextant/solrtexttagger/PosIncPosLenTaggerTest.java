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

import junit.framework.Assert;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test the {@link org.opensextant.solrtexttagger.TaggerRequestHandler}.
 */
public class PosIncPosLenTaggerTest extends AbstractTaggerTest {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");
  }

  /** tests support for alternate tokens as added by the 
   * <code>SynonymFilterFactory</code> */
  @Test
  public void testAlternates() throws Exception {
    this.requestHandler = "/tag2";
    this.overlaps = "LONGEST_DOMINANT_RIGHT";

    buildNames("16 GB Memory Stick");

    assertTags("Where to buy a 16 GB Memory Stick", "16 GB Memory Stick");
    assertTags("Where to buy a 16 Gigabyte Memory Stick", "16 Gigabyte Memory Stick");
    assertTags("Where to buy a 16 GiB Memory Stick", "16 GiB Memory Stick");
    
    //also test alternatives at the begin of the name
    buildNames("Television");
    assertTags("Season 24 of the Simpsons will be in television bext month", "television");
    assertTags("An Internet TV service providing on demand access.", "TV");
    
    buildNames("Domain Name Service");
    assertTags("The DNS server is down.", "DNS");
    
    buildNames("DNS");
    assertTags("The DNS server is down.", "DNS");
    assertTags("The Domain Name Service server is down.", "Domain Name Service");
  }

  /**
   * Multi token synonyms can not be supported as explained in the limitation
   * section of <a href="http://blog.mikemccandless.com/2012/04/lucenes-tokenstreams-are-actually.html" >
   * @throws Exception
   */
  @Test
  public void testUnsupportedMultiTokenSynonyms() throws Exception {
    this.requestHandler = "/tag2";
    this.overlaps = "LONGEST_DOMINANT_RIGHT";

    try {
      buildNames("USA"); //mapped to "United States of America" -- FAILS
      Assert.fail("expects an UnsupportedTokenException!");
    } catch (RuntimeException e) {
      //expects an UnsupportedTokenException as cause
      Assert.assertTrue(e.getCause() instanceof UnsupportedTokenException);
    }
  }
  
  /**
   * Stop words are just removed from the token stream. So this is not expected
   * to cause any troubles.<p>
   * However future versions of the <code>solr.StopFilterFactory</code> might
   * use a PosInc of <code>2</code> what could cause troubles. 
   * So it is good to have this check in place
   * @throws Exception
   */
  @Test
  public void testStopWords() throws Exception {
    this.requestHandler = "/tag2";
    this.overlaps = "LONGEST_DOMINANT_RIGHT";

    buildNames("University of Ottawa");

    assertTags("Let us meet at the University of Ottawa in 20min.", "University of Ottawa");
  }
  
  /**
   * The <code>solr.WordDelimiterFilterFactory</code> can also generate alternate
   * tokens (<code>posInc == 0</code>).
   * 
   * <b>NOTE:<b>
   * 
   * <a href="https://github.com/OpenSextant/SolrTextTagger/issues/10#issuecomment-23709180">
   * WordDelimiterFilterFactory can not be supported</a> until it generates
   * correct <code>posInc</code> and <code>posLen</code> values!
   * 
   * @throws Exception
   */
  @Test
  public void testWordDelimiter() throws Exception {
    this.requestHandler = "/tag2";
    this.overlaps = "LONGEST_DOMINANT_RIGHT";

    buildNames("Wi-Fi");

    assertTags("My Wi-Fi network is not working.", "Wi-Fi");
    assertTags("My Wifi network is not working.", "Wifi");
    assertTags("My Wi fi network is not working.", "Wi fi");
  }

  @Test
  public void testSynonymsAndDelimiterCombinded() throws Exception {
    this.requestHandler = "/tag2";
    this.overlaps = "LONGEST_DOMINANT_RIGHT";

    buildNames("kml2gpx converter");

    assertTags("In need a KML to GPX converter", "KML to GPX converter");
  }

}
