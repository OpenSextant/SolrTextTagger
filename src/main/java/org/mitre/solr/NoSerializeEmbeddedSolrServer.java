package org.mitre.solr;

import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.FastOutputStream;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.BinaryResponseWriter;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.DocList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * An embedded SolrServer that does not round-trip serialize the response.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
public class NoSerializeEmbeddedSolrServer extends EmbeddedSolrServer {

  public NoSerializeEmbeddedSolrServer(CoreContainer coreContainer,
                                       String coreName) {
    super(coreContainer, coreName);
  }

  @SuppressWarnings("unchecked")
  @Override
  public NamedList<Object> getParsedResponse(SolrQueryRequest req,
                                             SolrQueryResponse rsp) {
    //return super.getParsedResponse(req, rsp);
    NamedList<Object> result = rsp.getValues();
    Iterator<Map.Entry<String, Object>> iter = result.iterator();
    while (iter.hasNext()) {
      Map.Entry<String, Object> entry = iter.next();
      if (entry.getValue() instanceof DocList) {
        DocList docList = (DocList) entry.getValue();
        final SolrDocumentList solrDocumentList = new SolrDocumentList();
        entry.setValue(solrDocumentList);
        solrDocumentList.setNumFound(docList.matches());
        solrDocumentList.setStart(docList.offset());
        solrDocumentList.setMaxScore(docList.maxScore());

        BinaryResponseWriter.Resolver resolver = new BinaryResponseWriter.Resolver(req, rsp.getReturnFields());
        ResultContext ctx = new ResultContext();
        ctx.docs = docList;
        try {
          resolver.writeResults(ctx, new JavaBinCodec(resolver) {
            {
              daos = new FastOutputStream(new ByteArrayOutputStream());//dummy
            }

            @Override
            public void writeSolrDocument(SolrDocument doc) throws IOException {
              solrDocumentList.add(doc);
            }
          });
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }//if DocList
    }//loop

    return result;
  }


}
