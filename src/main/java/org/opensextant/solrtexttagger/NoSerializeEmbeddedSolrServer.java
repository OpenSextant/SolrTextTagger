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

  @SuppressWarnings({"unchecked", "deprecation"})
  @Override
  public NamedList<Object> getParsedResponse(SolrQueryRequest req,
                                             SolrQueryResponse rsp) {
    //return super.getParsedResponse(req, rsp);
    NamedList<Object> result = rsp.getValues();
    for (Map.Entry entry : result) {
      if (entry.getValue() instanceof ResultContext) {
        ResultContext ctx = (ResultContext) entry.getValue();
        entry.setValue(luceneDocListToSolrDocList(req, rsp, ctx.docs));
      } else if (entry.getValue() instanceof DocList) {
        DocList docList = (DocList) entry.getValue();
        entry.setValue(luceneDocListToSolrDocList(req, rsp, docList));
      }//if DocList
    }//loop

    return result;
  }

  private SolrDocumentList luceneDocListToSolrDocList(SolrQueryRequest req, SolrQueryResponse rsp, DocList docList) {
    final SolrDocumentList solrDocumentList = new SolrDocumentList();
    solrDocumentList.setNumFound(docList.matches());
    solrDocumentList.setStart(docList.offset());
    solrDocumentList.setMaxScore(docList.maxScore());

    BinaryResponseWriter.Resolver resolver =
        new BinaryResponseWriter.Resolver(req, rsp.getReturnFields());
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
    return solrDocumentList;
  }


}
