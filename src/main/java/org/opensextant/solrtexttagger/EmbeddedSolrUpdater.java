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

import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.SolrResponseBase;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrResourceLoader;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author David Smiley - dsmiley@mitre.org
 */
public class EmbeddedSolrUpdater {

  //It would be nice to somehow extend Solr's SimplePostTool (post.jar) but
  // that is much work than what this simple java program does.
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("First arg: --input=<csv>   Names CSV file. 'stdin' for System.stdin\n\n");
      System.err.println("This program takes a series of Solr url paths as arguments.   the first should start with '/update' but is more likely:");
      System.err.println("  /update?update.contentType=text/csv&optimize=true&separator=%09&trim=on&f.SOURCE_FEATURE_ID.map=1.0:1");
      System.err.println("Other URLs.  And remember to set Solr home via -Dsolr.sorl.home=...");
      return;
    }

    // getopts? parse --input=FILE
    final String csv = args[0].split("=", 2)[1];

    SolrServer solrServer = createSolrServer();
    try {
      final AtomicBoolean consumedStdin = new AtomicBoolean(false);
      for (String url : args) {

        if (url.startsWith("--input")) {
          continue;
        }

        System.out.println("Processing " + url);
        int qIdx = url.indexOf('?');
        String requestHandler = url.substring(0, qIdx);
        String urlQuery = url.substring(qIdx + 1);
        final ModifiableSolrParams params = getUrlParameters(urlQuery);

        boolean hasStreamParam = false;
        for (Map.Entry<String, Object> entry : params.toNamedList()) {
          if (entry.getKey().startsWith("stream.")) {
            hasStreamParam = true;
            break;
          }
        }

        SolrRequest solrRequest;
        if (requestHandler.startsWith("/update") && !hasStreamParam) {
          //send data via stdin
          if (consumedStdin.get()) {
            throw new RuntimeException("Multiple URLs assume need for stdin; can only do 1.");
          }
          final ContentStreamUpdateRequest updateRequest = new ContentStreamUpdateRequest(requestHandler);
          updateRequest.setParams(params);

          // Manage I/O -- from stdin or read from file
          //
          updateRequest.addContentStream(new ContentStreamBase() {
            {
              super.contentType = params.get(UpdateParams.ASSUME_CONTENT_TYPE);
            }

            @Override
            public InputStream getStream() throws IOException {
              if (csv.equals("stdin")) {
                consumedStdin.set(true);
                return new BufferedInputStream(System.in);
              }

              // Read from given CSV
              return new BufferedInputStream(new FileInputStream(csv));
            }
          });


          solrRequest = updateRequest;
        } else {
          solrRequest = new QueryRequest(params, SolrRequest.METHOD.GET);
          solrRequest.setPath(requestHandler);
        }

        SolrResponseBase response = (SolrResponseBase) solrRequest.process(solrServer);
        printResponse(response);
      }

    } finally {
      solrServer.shutdown();
    }
  }

  private static void printResponse(SolrResponseBase response) {
    int status = response.getStatus();
    PrintStream out = status == 0 ? System.out : System.err;
    out.println(response.toString());
  }

  private static EmbeddedSolrServer createSolrServer() {
    //This init sequence is a little hokey
    String solrHome = SolrResourceLoader.locateSolrHome();
    //There was an API change from Solr 4.3 to Solr 4.4
    //The CoreContainer constructor with 'String, File' was removed in favour
    //of one that only takes a 'String' parameter. In addition when using the
    //'String' only constructor in Solr 4.4 one needs to explicitly #load()
    //This method uses reflection to keep support for Solr 4.0 TO 4.4+
    boolean solr44;
    Constructor<CoreContainer> coreContainerConstructor;
    Method coreContainerLoad;
    try {
      coreContainerConstructor = CoreContainer.class.getConstructor(
          new Class[]{ String.class, File.class});
      solr44 = false; //this constructor was removed with Solr 4.4+
      coreContainerLoad = null; //there is no load method in Solr 4.3
    } catch (NoSuchMethodException e) { //that means we have Solr 4.4+
      try {
        coreContainerConstructor = CoreContainer.class.getConstructor(
            new Class[]{String.class});
        solr44 = true;
        coreContainerLoad = CoreContainer.class.getMethod("load");
      } catch (NoSuchMethodException e1) {
        throw new IllegalStateException("Unsupported Solr version",e);
      }
    }
    CoreContainer coreContainer;
    try {
      if(solr44){
        coreContainer = coreContainerConstructor.newInstance(solrHome);
        coreContainerLoad.invoke(coreContainer); //in Solr 4.4 we need to call load!
      } else {
        File cfgFile = new File(solrHome, "solr.xml");
        coreContainer = coreContainerConstructor.newInstance(solrHome, cfgFile);
      }
    } catch (InstantiationException e) {
        throw new IllegalStateException("Unsupported Solr version",e);
    } catch (IllegalAccessException e) {
        throw new IllegalStateException("Unsupported Solr version",e);
    } catch (InvocationTargetException e) {
        throw new IllegalStateException("Unsupported Solr version",e);
    }
    return new EmbeddedSolrServer(coreContainer, ""/*core name*/);
  }

  public static ModifiableSolrParams getUrlParameters(String query)
          throws UnsupportedEncodingException {
    ModifiableSolrParams solrParams = new ModifiableSolrParams();
    for (String param : query.split("&")) {
      String pair[] = param.split("=");
      String key = URLDecoder.decode(pair[0], "UTF-8");
      String value = "";
      if (pair.length > 1) {
        value = URLDecoder.decode(pair[1], "UTF-8");
      }
      solrParams.add(key, value);
    }
    return solrParams;
  }
}
