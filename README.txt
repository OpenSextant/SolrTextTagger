This project implements a "naive" text tagger based on Lucene / Solr, using
Lucene FST (Finite State Transducer) technology under the hood.  It is "naive"
because it does simple text word based substring tagging without consideration
of any natural language context.  It operates on the results of how you
configure text analysis in Lucene and so it's quite flexible to match things
like phonetics for sounds-like tagging if you wanted to.

If this sounds interesting, then watch this presentation about the SolrTextTagger
given at Lucene Revolution 2013, by David Smiley: http://www.youtube.com/watch?v=3kQyYbTyXfc

For more information on Lucene FSTs, the technological marvel that enables this
component to keep ten millions place names in a structure that is a mere 175MB,
see these resources:

* https://docs.google.com/presentation/d/1Z7OYvKc5dHAXiVdMpk69uulpIT6A7FGfohjHx8fmHBU/edit#slide=id.p
* http://blog.mikemccandless.com/2010/12/using-finite-state-transducers-in.html
* http://blog.mikemccandless.com/2011/01/finite-state-transducers-part-2.html

Contributors:
  * David Smiley (MITRE)

======== Build Instructions

  * Maven (preferred):
    To compile and run tests, use:
    %> mvn test
    To compile, test, and build the jar (placed in target/), use:
    %> mvn package

  * Ant:
    To compile and build (placed in build/), use:
    %> ant

Configuration

======== Configuration

A Solr schema.xml needs 2 things:
 * A unique key field  (see <uniqueKey>).
 * A name/lookup field indexed with Shingling or ConcatenateFilter.

Assuming you want to support typical keyword search on the names, you'll index
the names separately in another field with a different field type configuration than the
configuration described here.
The name field's index analyzer needs to end in either Shingling for "partial"
(i.e. sub name phrase) matching, or more likely using ConcatenateFilter for full matching.
Don't do it for the query time analysis. ConcatenateFilter acts similar to shingling but it
concatenates all tokens into one final token with a space separator.

For the indexed name data, the text analysis should result in
consecutive positions (i.e. the position increment of each term must always be
1).  So, be careful with use of stop words, synonyms, WordDelimiterFilter, and
potentially others.  On the other hand, if the input text
has a position increment greater than one then it is handled properly as if an
unknown word was there.  It's plausible that
this restriction might be lifted in the future but it is tricky -- see this for
more info:
  http://blog.mikemccandless.com/2012/04/lucenes-tokenstreams-are-actually.html

To make the tagger work as fast as possible, configure the name field with
postingsFormat="Memory"; you'll have to add this to solrconfig.xml to use that
advanced Solr feature:
  <codecFactory name="CodecFactory" class="solr.SchemaCodecFactory" />

Here is a sample field type config that should work quite well:

  <fieldType name="tag" class="solr.TextField" positionIncrementGap="100" postingsFormat="Memory"
      omitTermFreqAndPositions="true" omitNorms="true">
    <analyzer type="index">
      <tokenizer class="solr.StandardTokenizerFactory"/>
      <filter class="solr.EnglishPossessiveFilterFactory" />
      <filter class="solr.ASCIIFoldingFilterFactory"/>
      <filter class="solr.LowerCaseFilterFactory" />

      <filter class="org.opensextant.solrtexttagger.ConcatenateFilterFactory" />
    </analyzer>
    <analyzer type="query">
      <tokenizer class="solr.StandardTokenizerFactory"/>
      <filter class="solr.EnglishPossessiveFilterFactory" />
      <filter class="solr.ASCIIFoldingFilterFactory"/>
      <filter class="solr.LowerCaseFilterFactory" />
    </analyzer>
  </fieldType>

A Solr solrconfig.xml needs a special request handler, configured like this:

  <requestHandler name="/tag" class="org.opensextant.solrtexttagger.TaggerRequestHandler">
    <str name="field">name_tag</str>
    <str name="fq">NOT name:(of the)</str><!-- filter out -->
  </requestHandler>

 * field: The field that represents the corpus to match on, as described above.
 * fq: (optional) A query that matches the subset of documents for name matching.

======== Usage

For tagging, you HTTP POST data to Solr similar to how the ExtractingRequestHandler
(Tika) is invoked.  A request invoked via the "curl" program could look like this:

curl -XPOST \
  'http://localhost:8983/solr/tag?overlaps=NO_SUB&tagsLimit=5000&fl=*' \
  -H 'Content-Type:text/plain' -d @/mypath/myfile.txt

The tagger request-time parameters are:
 * overlaps: choose the algorithm to determine which overlapping tags should be
 retained, versus being pruned away.  See below...
 * matchText: A boolean indicating whether to return the matched text in the tag
 response.
 * tagsLimit: The maximum number of tags to return in the response.  Tagging
 effectively stops after this point.  By default this is 1000.
 * rows: Solr's standard param to say the maximum number of documents to return,
 but defaulting to 10000 for a tag request.
 * skipAltTokens: A boolean flag used to suppress errors that can occur if, for
 example, you enable synonym expansion at query time in the analyzer, which you
 normally shouldn't do. Let this default to false unless you know that such
 tokens can't be avoided.
 * fl: Solr's standard param for listing the fields to return.
 * Most other standard parameters for working with Solr response formatting:
 echoParams, wt, indent, etc.

Options for the "overlaps" parameter:
 ALL: Emit all tags.
 NO_SUB: Don't emit a tag that is completely within another tag (i.e. no subtag).
 LONGEST_DOMINANT_RIGHT: Given a cluster of overlapping tags, emit the longest
  one (by character length). If there is a tie, pick the right-most. Remove
  any tags overlapping with this tag then repeat the algorithm to potentially
  find other tags that can be emitted in the cluster.

The output is broken down into two parts, first an array of tags, and then
Solr documents referenced by those tags.  Each tag has the starting character
offset, an ending character (+1) offset, and the Solr unique key field value.
The Solr documents part of the response is Solr's standard search results
format.
