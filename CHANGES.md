This file records changes to the SolrTextTagger.  It has Solr & Java version compatibility info too.

NOTE: There are three independent versions of the tagger: the one in Apache Solr 7.4.0, the GitHub latest 2.x and GitHub latest 1.x.
2.x does not support synonyms (posInc=0) analysis but the others do.  Only 2.x supports htmlOffsetAdjust.

The [.travis.yml file](.travis.yml) shows the current testing version matrix
on master.  Older releases will show older tested releases working at
those times.

The TaggerHandler in Apache Solr 7.4.0 is based on 2.6-SNAPSHOT, and has other changes.

## Version 2.6-SNAPSHOT (unreleased)

* Performance: Avoid calling terms.iterator() when not needed
* Notice: Lucene's postingsFormat="Memory" option will be removed imminently.
  So use "FST50" which is nearly as good.

## Version 2.5, March 27th, 2018

Compatible with Solr 7.0, 7.1, 7.2, 7.3, ...

## Version 2.4, February 11th, 2017

Compatible with Solr 6.3, 6.4.1, ... ?

Compiled for Java 1.8.

* #61 'fq' is now multi-valued

## Version 2.3, July 20th, 2016

Compatible with Solr 5.3 thru 6.2.1

Compiled for Java 1.7.

## Version 2.2, December 16th, 2015

Compatible with Solr 5.2

Compiled for Java 1.7.

## Version 2.1, August 12th, 2015

Compatible with Solr 5.0 thru 5.1.

Compiled for Java 1.7.

## Version 2.0, January 26th, 2015

Compatible with Solr 4.3 thru 4.10.

Compiled for Java 1.6.

This is a major release that fundamentally changes the underlying engine from working directly off
of an FST to one working off a Lucene TermsEnum configured to be backed by an FST.  The
schema and configuration has changed some accordingly, but the tagger request API hasn't changed.
The tagger's codebase shrunk too as Lucene manages more of the complexity.
The internal name entries are now encoded as a char delimited phrase _instead of_ a word dictionary
with word ID phrases.  This approach reduced the memory and disk requirements substantially
from 1.x.  40% less?

IMPORTANT: One feature *not* yet ported from 1.x is support for index-time expanding synonyms
and the catenate options of WordDelimiterFilter (or other analysis resulting in tokens at the
same position).  Consequently, don't do those things in your index analysis chain :-/

 * 'xmlOffsetAdjust' option.  See README.md

 * 'htmlOffsetAdjust' option.  See README.md

 * 'nonTaggableTags' option.  See README.md
 
 * Removed deprecated NoSerializeEmbeddedSolrServer & EmbeddedSolrUpdater (\#21)

## Version 1.2 (and prior), October 2nd 2013

Compatible with Solr 4.2 thru 4.4; later 4.x releases may or may not work.

Compiled for Java 1.6.

 * Supports index-time expanding synonyms and the catenate options of WordDelimiterFilter, or most
 other analysis at index time wherein tokens are generated at the same position.
 Multi-word synonyms are not supported unless you normalize at index & query to a single-word
 variant (i.e. "domain name system" -> "dns").
 Internally, this done by PhraseBuilder and is tested in PosIncPosLenTaggerTest.
 Thanks to Rupert Westenthaler!  (\#10)
