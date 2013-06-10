package org.mitre.solr.tagger;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ContentStreamBase;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
    solrServer = new EmbeddedSolrServer(h.getCoreContainer(), null);
    //we don't need to close the EmbeddedSolrServer because SolrTestCaseJ4 closes the core
  }

  @Test
  public void test() throws SolrServerException, IOException {
    solrServer.deleteByQuery("*:*");
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", "999");
    doc.addField("name", "Boston");
    solrServer.add(doc);
    solrServer.commit();

    ModifiableSolrParams params = new ModifiableSolrParams();
    String input = "foo boston bar";//just one tag;
    QueryRequest req = new SolrTaggerRequest(params, input);
    req.setPath("/tag");
    MyStreamingResponseCallback streamingResponseCallback = new MyStreamingResponseCallback();
    req.setStreamingResponseCallback(streamingResponseCallback);

    QueryResponse rsp = req.process(solrServer);
    assertNull(rsp.getResults());
    assertNotNull(rsp.getResponse().get("tags"));
    assertNotNull(streamingResponseCallback.foundStreaming.get(0));
  }

  private static class MyStreamingResponseCallback extends StreamingResponseCallback {
    List<SolrDocument> foundStreaming;

    @Override
    public void streamSolrDocument(SolrDocument doc) {
      foundStreaming.add(doc);
    }

    @Override
    public void streamDocListInfo(long numFound, long start, Float maxScore) {
      foundStreaming = new ArrayList<SolrDocument>((int)numFound);
    }
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
