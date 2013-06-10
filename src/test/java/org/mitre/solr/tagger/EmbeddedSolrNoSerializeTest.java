package org.mitre.solr.tagger;

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
import org.mitre.solr.NoSerializeEmbeddedSolrServer;

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
