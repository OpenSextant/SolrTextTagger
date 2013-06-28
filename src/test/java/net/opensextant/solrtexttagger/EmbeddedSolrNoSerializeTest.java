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

package net.opensextant.solrtexttagger;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ContentStreamBase;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

/**
 * Tests that we can skip serialization of the documents when embedding
 * Solr.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
public class EmbeddedSolrNoSerializeTest extends SolrTestCaseJ4 {

  EmbeddedSolrServer solrServer;

  @Before
  public void setUp() throws Exception {
    super.setUp();
    initCore("solrconfig.xml", "schema.xml");
    solrServer = new NoSerializeEmbeddedSolrServer(h.getCoreContainer(), null);
    //we don't need to close the EmbeddedSolrServer because SolrTestCaseJ4 closes the core
  }

  @Test
  public void test() throws SolrServerException, IOException {
    clearIndex();
    assertU(adoc("id", "9999", "name", "Boston"));
    assertU(commit());

    ModifiableSolrParams params = new ModifiableSolrParams();
    String input = "foo boston bar";//just one tag;
    QueryRequest req = new SolrTaggerRequest(params, input);
    req.setPath("/tag");

    QueryResponse rsp = req.process(solrServer);
    SolrDocumentList results= (SolrDocumentList) rsp.getResponse().get("matchingDocs");
    assertNotNull(rsp.getResponse().get("tags"));
    assertNotNull(results.get(0));
  }

  @SuppressWarnings("serial")
  public static class SolrTaggerRequest extends QueryRequest {

    private final String input;

    public SolrTaggerRequest(SolrParams p, String input) {
      super(p, METHOD.POST);
      this.input = input;
    }

    @Override
    public Collection<ContentStream> getContentStreams() {
      return Collections.singleton((ContentStream) new ContentStreamBase
          .StringStream(input));
    }
  }
}
