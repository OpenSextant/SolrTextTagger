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

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter;
import org.apache.solr.schema.FieldType;
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
    //This would fail, as the WordDelimiterFilter would split 'GiB' to 'gi', 'b'
    // but this is not indexed in the FST
    //assertTags("Where to buy a 16 GiB Memory Stick", "16 GiB Memory Stick");
    
    //The upper case & lower case version do work however
    assertTags("Where to buy a 16 GIB Memory Stick", "16 GIB Memory Stick");
    assertTags("Where to buy a 16 gib memory stick", "16 gib memory stick");
    
    
    //also test alternatives at the beginning of the name
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
   * <b>NOTE:</b> This test fails if <code>preserveOriginal="1"</code>
   * for the {@link WordDelimiterFilter} in the 
   * {@link FieldType#getQueryAnalyzer() QueryAnalyzer}<p>
   * The key of the problem is that the {@link Tagger} currently selects the
   * first token for a given <code>posInc</code> and <code>offset</code>. However
   * the 'preserveOriginal="1"' will cause the {@link WordDelimiterFilter} to
   * emit the token as set by the {@link Tokenizer} as first one. This token 
   * will include possible punctuation marks. However such tokens (incl. 
   * punctuation marks) will not be contained in the FST.
   * <p>
   * 
   * <b>TODO:</b> T
   **/
  @Test
  public void testWhitespaceTokenizerWithWordDelimiterFilter() throws Exception {
      this.requestHandler = "/tag2";
      this.overlaps = "LONGEST_DOMINANT_RIGHT";

      buildNames("Memory Stick");
      assertTags("Memory Stick.", "Memory Stick");

  }

  /**
   * Multi-token synonyms cannot be supported as explained in the limitation
   * section of <a href="http://blog.mikemccandless.com/2012/04/lucenes-tokenstreams-are-actually.html" >
   *   link</a>
   *
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
   * use a PosInc of <code>2</code> which could cause troubles
   * so it is good to have this check in place.
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
  public void testSynonymsAndDelimiterCombined() throws Exception {
    this.requestHandler = "/tag2";
    this.overlaps = "LONGEST_DOMINANT_RIGHT";

    buildNames("kml2gpx converter");

    assertTags("In need a KML to GPX converter", "KML to GPX converter");
  }

  /**
   * {@link WordDelimiterFilter} may create alternate Tokens that are later
   * on removed e.g. by a stop word filter. Those patterns need special
   * treatment as the resulting token stream will contain tokens with
   * <code>posInc=1</code> but no fitting token where it can originate. In
   * such cases the {@link PhraseBuilder} needs to find the correct anchor 
   * point of such tokens and create a new branch for the removed token.
   * @throws Exception
   */
  @Test
  public void testRemovalOfAlternateTokens() throws Exception {
      this.requestHandler = "/tag2";
      this.overlaps = "LONGEST_DOMINANT_RIGHT";

      
      // 'o' is a stopword for some languages.   
      buildNames("Ronnie O'Sullivan"); //NOTE added 'o' to stopword list
      //this tests that it gets correctly added to the FST even that 'o' will
      //be removed.
      assertTags("Ronnie O'Sullivan's fist match after winning the Championship", "Ronnie O'Sullivan");

      //In this case 'The' will be removed. So this tests that ['B-52'], ['B','20']
      //and ['B52'] are correctly added to the FST even that the starting node is
      //removed.
      buildNames("The B-52");
      assertTags("The Boeing B-52 Stratofortress is a strategic bomber", "B-52");
      assertTags("The Boeing B 52 Stratofortress is a strategic bomber", "B 52");
      assertTags("The Boeing B52 Stratofortress is a strategic bomber", "B52");

      
      //here a more generic test ensuring that 'a' being removed does not
      //break FST indexing and tagging
      buildNames("Mr A.St."); // 'a' is a stopword and gets removed
      assertTags("Hallo Mr St. how is life", "Mr St"); //so we find this even without A
      assertTags("Dear Mr ASt. we would like to", "Mr ASt"); //concatenation at indexing time
      assertTags("Mr A St. this is important", "Mr A St"); //create word parts
      assertTags("Dear Mr A.St. can you find", "Mr A.St"); //the original mention
      
  }
  
}
