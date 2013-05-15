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

package org.mitre.solr.tagger;

import org.apache.commons.lang.builder.CompareToBuilder;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.lucene.document.Document;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocList;
import org.apache.solr.search.SolrIndexSearcher;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Test the {@link org.mitre.solr.tagger.TaggerRequestHandler}.
 */
public class TaggerTest extends SolrTestCaseJ4 {

  private String requestHandler;//qt param
  private String overlaps;//param

  private final Logger log = LoggerFactory.getLogger(getClass());

  @Rule
  public TestWatcher watchman = new TestWatcher() {
    @Override
    protected void starting(Description description) {
      log.info("{} being run...", description.getDisplayName());
    }
  };

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");
  }

  private void indexAndBuild(String... buildParams) throws Exception {
    addCorpus();
    assertU(commit());

    //build
    ModifiableSolrParams params = newParams(buildParams);
    params.add("build", "on");
    assertQ(req(params));
  }

  private SolrQueryRequest req(SolrParams params) {
    NamedList<Object> nl = params.toNamedList();
    String[] strs = new String[nl.size()*2];
    int i = 0;
    for (Map.Entry entry : nl) {
      strs[i++] = entry.getKey().toString();
      strs[i++] = entry.getValue().toString();
    }
    return super.req(strs);
  }

  private ModifiableSolrParams newParams(String... moreParams) {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(CommonParams.QT, requestHandler);
    assert this.overlaps != null;
    params.set("overlaps", this.overlaps);
    addMoreParams(params, moreParams);
    return params;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();//TODO
    requestHandler = "/tag";
    overlaps = null;
  }

  /** Name corpus */
  enum N {
    //keep order to retain ord()
    London, London_Business_School, Boston, City_of_London,
    of, the//filtered out of the corpus by a custom query
    ;

    String getName() { return name().replace('_',' '); }
    static N lookupByName(String name) { return N.valueOf(name.replace(' ', '_')); }
    int getId() { return ordinal(); }
  }

  private void addCorpus() {
    for (N name : N.values()) {
      assertU(adoc("id",name.getId()+"","name",name.getName()));
    }
  }

  @Test
  public void testFormat() throws Exception {
    requestHandler = "/tagPartial";
    overlaps = "NO_SUB";
    indexAndBuild();

    String rspStr = _testFormatRequest(false);
    String expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<response>\n" +
        "<int name=\"tagsCount\">1</int>" +
        "<arr name=\"tags\"><lst>" +
          "<int name=\"startOffset\">0</int>" +
          "<int name=\"endOffset\">6</int>" +
          "<arr name=\"ids\"><int>1</int></arr>" +
        "</lst></arr>" +
        "<result name=\"matchingDocs\" numFound=\"1\" start=\"0\">" +
          "<doc><int name=\"id\">1</int><str name=\"name\">London Business School</str></doc>" +
        "</result>\n" +
        "</response>\n";
    assertEquals(expected, rspStr);
  }

  @Test
  public void testFormatMatchText() throws Exception {
    requestHandler = "/tagPartial";
    overlaps = "NO_SUB";
    indexAndBuild();

    String rspStr = _testFormatRequest(true);
    String expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<response>\n" +
        "<int name=\"tagsCount\">1</int>" +
        "<arr name=\"tags\"><lst>" +
          "<int name=\"startOffset\">0</int>" +
          "<int name=\"endOffset\">6</int><" +
          "str name=\"matchText\">school</str>" +
          "<arr name=\"ids\"><int>1</int></arr>" +
        "</lst></arr>" +
        "<result name=\"matchingDocs\" numFound=\"1\" start=\"0\">" +
          "<doc><int name=\"id\">1</int><str name=\"name\">London Business School</str></doc>" +
        "</result>\n" +
        "</response>\n";
    assertEquals(expected, rspStr);
  }

  private String _testFormatRequest(boolean matchText) throws Exception {
    String doc = "school";//just one tag
    SolrQueryRequest req = reqDoc(doc, "indent", "off", "omitHeader", "on", "matchText", ""+matchText);
    String rspStr = h.query(req);
    req.close();
    return rspStr;
  }

  @Test
  /** Partial matching, no sub-tags */
  public void testPartialMatching() throws Exception {
    requestHandler = "/tagPartial";
    overlaps = "NO_SUB";
    indexAndBuild();

    //these match nothing
    assertTags(reqDoc("") );
    assertTags(reqDoc(" ") );
    assertTags(reqDoc("the") );

    String doc;

    //just London Business School via "school" substring
    doc = "school";
    assertTags(reqDoc(doc), tt(doc,"school", 0, N.London_Business_School));

    doc = "a school";
    assertTags(reqDoc(doc), tt(doc,"school", 0, N.London_Business_School));

    doc = "school a";
    assertTags(reqDoc(doc), tt(doc,"school", 0, N.London_Business_School));

    //More interesting

    doc = "school City";
    assertTags(reqDoc(doc),
        tt(doc, "school", 0, N.London_Business_School),
        tt(doc, "City", 0, N.City_of_London) );

    doc = "City of London Business School";
    assertTags(reqDoc(doc),   //no plain London (sub-tag)
        tt(doc, "City of London", 0, N.City_of_London),
        tt(doc, "London Business School", 0, N.London_Business_School));
  }

  @Test
  /** whole matching, no sub-tags */
  public void testWholeMatching() throws Exception {
    overlaps = "NO_SUB";
    indexAndBuild();

    //these match nothing
    assertTags(reqDoc(""));
    assertTags(reqDoc(" ") );
    assertTags(reqDoc("the") );

    //partial on N.London_Business_School matches nothing
    assertTags(reqDoc("school") );
    assertTags(reqDoc("a school") );
    assertTags(reqDoc("school a") );
    assertTags(reqDoc("school City") );

    String doc;

    doc = "school business london";//backwards
    assertTags(reqDoc(doc), tt(doc,"london", 0, N.London));

    doc = "of London Business School";
    assertTags(reqDoc(doc),   //no plain London (sub-tag)
        tt(doc, "London Business School", 0, N.London_Business_School));

    //More interesting
    doc = "City of London Business School";
    assertTags(reqDoc(doc),   //no plain London (sub-tag)
        tt(doc, "City of London", 0, N.City_of_London),
        tt(doc, "London Business School", 0, N.London_Business_School));

    doc = "City of London Business";
    assertTags(reqDoc(doc),   //no plain London (sub-tag) no Business (partial-match)
        tt(doc, "City of London", 0, N.City_of_London));

    doc = "London Business magazine";
    assertTags(reqDoc(doc),  //Just London; L.B.S. fails
        tt(doc, "London", 0, N.London));
  }

  @Test
  /** whole matching, with sub-tags */
  public void testSubTags() throws Exception {
    overlaps = "ALL";
    indexAndBuild();

    //these match nothing
    assertTags(reqDoc(""));
    assertTags(reqDoc(" ") );
    assertTags(reqDoc("the") );

    //partial on N.London_Business_School matches nothing
    assertTags(reqDoc("school") );
    assertTags(reqDoc("a school") );
    assertTags(reqDoc("school a") );
    assertTags(reqDoc("school City") );

    String doc;

    doc = "school business london";//backwards
    assertTags(reqDoc(doc), tt(doc,"london", 0, N.London));

    //More interesting
    doc = "City of London Business School";
    assertTags(reqDoc(doc),
        tt(doc, "City of London", 0, N.City_of_London),
        tt(doc, "London", 0, N.London),
        tt(doc, "London Business School", 0, N.London_Business_School));

    doc = "City of London Business";
    assertTags(reqDoc(doc),
        tt(doc, "City of London", 0, N.City_of_London),
        tt(doc, "London", 0, N.London));
  }
  
  private TestTag tt(String doc, String substring, int substringIndex, N name) {
    int startOffset = -1, endOffset;
    for(int i = 0; i <= substringIndex; i++) {
      startOffset = doc.indexOf(substring,++startOffset);
      assert startOffset >= 0 : "The test itself is broken";
    }
    endOffset = startOffset+substring.length();//1 greater (exclusive)
    return new TestTag(startOffset, endOffset, substring, name);
  }

  /** Asserts the tags.  Will call req.close(). */
  @SuppressWarnings("unchecked")
  private void assertTags(SolrQueryRequest req, TestTag... aTags) throws Exception {
    try {
      Arrays.sort(aTags);
      SolrQueryResponse rsp = h.queryAndResponse(req.getParams().get(CommonParams.QT, requestHandler), req);
      assertNotNull(rsp.getResponseHeader().get("QTime"));
      NamedList rspValues = rsp.getValues();

      //build matchingNames map from matchingDocs doc list in response
      Map<Integer,N> matchingNames = new HashMap<Integer, N>();
      SolrIndexSearcher searcher = req.getSearcher();
      DocList docList = (DocList) rspValues.get("matchingDocs");
      DocIterator iter = docList.iterator();
      while (iter.hasNext()) {
        int docId = iter.next();
        Document doc = searcher.doc(docId);
        Integer id = (Integer) doc.getField("id").numericValue();
        N name = N.lookupByName(doc.get("name"));
        assert name.getId() == id.intValue();
        matchingNames.put(id, name);
      }

      //build TestTag[] mTags from response then assert equals
      List<NamedList> mTagsList = (List<NamedList>) rspValues.get("tags");
      TestTag[] mTags = new TestTag[mTagsList.size()];
      int mt_i = 0;
      for (NamedList map : mTagsList) {
        List<Integer> foundIds = (List<Integer>) map.get("ids");
        for (Integer id  : foundIds) {
          mTags[mt_i++] = new TestTag(
              ((Number)map.get("startOffset")).intValue(),
              ((Number)map.get("endOffset")).intValue(),
              null,
              matchingNames.get(id));
        }
      }
      assertArrayEquals(aTags,mTags);
    } finally {
      req.close();
    }
  }

  /** REMEMBER to close() the result req object. */
  private SolrQueryRequest reqDoc(String doc, String... moreParams) {
    log.debug("Test doc: "+doc);
    ModifiableSolrParams params = newParams(moreParams);
    SolrQueryRequestBase req = new SolrQueryRequestBase(h.getCore(), params) {};
    Iterable<ContentStream> stream = Collections.singleton((ContentStream)new ContentStreamBase.StringStream(doc));
    req.setContentStreams(stream);
    return req;
  }

  private static void addMoreParams(ModifiableSolrParams params, String[] moreParams) {
    if (moreParams != null) {
      for (int i = 0; i < moreParams.length; i+= 2) {
        params.add(moreParams[i], moreParams[i+1]);
      }
    }
  }

  static class TestTag implements Comparable {
    final int startOffset, endOffset;
    final String substring;
    final N docName;

    TestTag(int startOffset, int endOffset, String substring, N docName) {
      this.startOffset = startOffset;
      this.endOffset = endOffset;
      this.substring = substring;
      this.docName = docName;
    }

    @Override
    public String toString() {
      return "TestTag{" +
          "[" + startOffset + "-" + endOffset + "]" +
          " docName=(" + docName.getId()+")" + docName +
          " substr="+substring+
          '}';
    }

    @Override
    public boolean equals(Object obj) {
      TestTag that = (TestTag) obj;
      return new EqualsBuilder()
          .append(this.startOffset, that.startOffset)
          .append(this.endOffset, that.endOffset)
          .append(this.docName, that.docName)
          .isEquals();
    }

    @Override
    public int hashCode() {
      return startOffset;//cheasy but correct
    }

    @Override
    public int compareTo(Object o) {
      TestTag that = (TestTag) o;
      return new CompareToBuilder()
          .append(this.endOffset, that.endOffset)
          .append(this.startOffset, that.startOffset)
          .append(docName.getId(),that.docName.getId())
          .toComparison();
    }
  }
}
