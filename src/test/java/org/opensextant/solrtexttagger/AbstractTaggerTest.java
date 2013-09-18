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
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author David Smiley - dsmiley@mitre.org
 */
public abstract class AbstractTaggerTest extends SolrTestCaseJ4 {

  protected final Logger log = LoggerFactory.getLogger(getClass());

  @Rule
  public TestWatcher watchman = new TestWatcher() {
    @Override
    protected void starting(Description description) {
      log.info("{} being run...", description.getDisplayName());
    }
  };

  protected String requestHandler;//qt param
  protected String overlaps;//param

  //populated in buildNames; tested in assertTags
  private List<String> NAMES;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    requestHandler = "/tag";
    overlaps = null;
  }

  private void addMoreParams(ModifiableSolrParams params, String[] moreParams) {
    if (moreParams != null) {
      for (int i = 0; i < moreParams.length; i+= 2) {
        params.add(moreParams[i], moreParams[i+1]);
      }
    }
  }

  private void build(String... buildParams) {
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
    return req(strs);
  }

  protected ModifiableSolrParams newParams(String... moreParams) {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(CommonParams.QT, requestHandler);
    assert this.overlaps != null;
    params.set("overlaps", this.overlaps);
    addMoreParams(params, moreParams);
    return params;
  }

  protected void assertTags(String doc, String... tags) throws Exception {
    TestTag[] tts = new TestTag[tags.length];
    for (int i = 0; i < tags.length; i++) {
      tts[i] = tt(doc, tags[i]);
    }
    assertTags(reqDoc(doc), tts);
  }

  protected void buildNames(String... names) throws Exception {
    deleteByQueryAndGetVersion("*:*", null);
    NAMES = Arrays.asList(names);
    //Collections.sort(NAMES);
    int i = 0;
    for (String n : NAMES) {
      assertU(adoc("id", ""+(i++), "name", n));
    }
    assertU(commit());
    build();
  }

  protected String lookupByName(String name) {
    for (String n : NAMES) {
      if (n.equalsIgnoreCase(name))
        return n;
    }
    return null;
  }

  protected TestTag tt(String doc, String substring) {
    int startOffset = -1, endOffset;
    int substringIndex = 0;
    for(int i = 0; i <= substringIndex; i++) {
      startOffset = doc.indexOf(substring,++startOffset);
      assert startOffset >= 0 : "The test itself is broken";
    }
    endOffset = startOffset+substring.length();//1 greater (exclusive)
    return new TestTag(startOffset, endOffset, substring, lookupByName(substring));
  }

  /** Asserts the tags.  Will call req.close(). */
  @SuppressWarnings("unchecked")
  protected void assertTags(SolrQueryRequest req, TestTag... aTags) throws Exception {
    try {
      Arrays.sort(aTags);
      SolrQueryResponse rsp = h.queryAndResponse(req.getParams().get(CommonParams.QT, requestHandler), req);
      NamedList rspValues = rsp.getValues();

      //build matchingNames map from matchingDocs doc list in response
      Map<String, String> matchingNames = new HashMap<String, String>();
      SolrIndexSearcher searcher = req.getSearcher();
      DocList docList = (DocList) rspValues.get("matchingDocs");
      DocIterator iter = docList.iterator();
      while (iter.hasNext()) {
        int docId = iter.next();
        Document doc = searcher.doc(docId);
        String id = doc.getField("id").stringValue();
        String name = lookupByName(doc.get("name"));
        assertEquals("looking for "+name, NAMES.indexOf(name)+"", id);
        matchingNames.put(id, name);
      }

      //build TestTag[] mTags from response then assert equals
      List<NamedList> mTagsList = (List<NamedList>) rspValues.get("tags");
      TestTag[] mTags = new TestTag[mTagsList.size()];
      int mt_i = 0;
      for (NamedList map : mTagsList) {
        List<String> foundIds = (List<String>) map.get("ids");
        for (String id  : foundIds) {
          mTags[mt_i++] = new TestTag(
              ((Number)map.get("startOffset")).intValue(),
              ((Number)map.get("endOffset")).intValue(),
              null,
              matchingNames.get(id));
        }
      }
      assertArrayEquals(Arrays.asList(mTags).toString(), aTags, mTags);
    } finally {
      req.close();
    }
  }

  /** REMEMBER to close() the result req object. */
  protected SolrQueryRequest reqDoc(String doc, String... moreParams) {
    log.debug("Test doc: "+doc);
    ModifiableSolrParams params = newParams(moreParams);
    SolrQueryRequestBase req = new SolrQueryRequestBase(h.getCore(), params) {};
    Iterable<ContentStream> stream = Collections.singleton((ContentStream)new ContentStreamBase.StringStream(doc));
    req.setContentStreams(stream);
    return req;
  }

  class TestTag implements Comparable {
    final int startOffset, endOffset;
    final String substring;
    final String docName;

    TestTag(int startOffset, int endOffset, String substring, String docName) {
      this.startOffset = startOffset;
      this.endOffset = endOffset;
      this.substring = substring;
      this.docName = docName;
    }

    @Override
    public String toString() {
      return "TestTag{" +
          "[" + startOffset + "-" + endOffset + "]" +
          " docName=(" + NAMES.indexOf(docName)+")" + docName +
          " substr="+substring+
          '}';
    }

    @Override
    public boolean equals(Object obj) {
      TestTag that = (Tagger2Test.TestTag) obj;
      return new EqualsBuilder()
          .append(this.startOffset, that.startOffset)
          .append(this.endOffset, that.endOffset)
          //NOTE: checking the string is no longer feasible with PosInc and
          //      PosLen support, as a mention in the text might match a different
          //      stored field (e.g. a synonym mentioned in the text will match
          //      the original field if the solr.SynonymFilterFactory added the
          //      synonym as alternate token)
          //      This should not affect tests as start/end offset do make
          //      unique selections assuming that this and that are using the
          //      same text.
          //.append(this.docName, that.docName)
          .isEquals();
    }

    @Override
    public int hashCode() {
      return startOffset;//cheasy but correct
    }

    @Override
    public int compareTo(Object o) {
      TestTag that = (Tagger2Test.TestTag) o;
      return new CompareToBuilder()
          .append(this.startOffset, that.startOffset)
          .append(this.endOffset, that.endOffset)
          .append(this.docName,that.docName)
          .toComparison();
    }
  }
}
