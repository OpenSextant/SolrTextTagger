package org.opensextant.solrtexttagger;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

/**
 * Simple TokenFilter that lookup only Tokens with more as the parsed number
 * of chars.<p>
 * <b>NOTE:</b>This implementation is only intended to be used as an example 
 * and for unit testing the {@link LookupAttribute} feature. Typically 
 * implementations will be based on NLP results (e.g. using POS tags or
 * detected Named Entities).
 *  
 * <b>Example Usage:</b><p>
 * Currently the usage requires to modify the Analyzer as defined by the 
 * <code>indexedField</code>. An alternative would be to allow the configuration
 * of a special FieldType in the schema.xml and use this Analyzer for processing
 * the text sent to the request.<p>
 * While the current solution is fine for direct API usage, defining the
 * Analyzer in the schema.xml would be better suitable for using this feature
 * with the {@link TaggerRequestHandler}.
 * 
 * <code><pre>
 *     Analyzer analyzer = req.getSchema().getField(indexedField).getType().getAnalyzer();
 *     //get the TokenStream from the Analyzer
 *     TokenStream baseStream = analyzer.tokenStream("", reader);
 *     //add a FilterStream that sets the LookupAttribute to the end
 *     TokenStream filterStream = new WordLengthLookupFilter(baseStream);
 *     //create the Tagger using the modified analyzer chain.
 *     new Tagger(corpus, filterStream, tagClusterReducer) {
 *     
 *         protected void tagCallback(int startOffset, int endOffset, long docIdsKey) {
 *             //implement the callback
 *         }
 *         
 *     }.process();
 * </pre></code>
 * <p>
 * <b>TODO:</b> I have no Idea how I can write a unit test, because modifying the
 * TokenStream parsed to the {@link Tagger} is currently not supported by the
 * {@link TaggerRequestHandler}. <p>

 * @author Rupert Westenthaler
 *
 */
public class WordLengthLookupFilter extends TokenFilter {

    /**
     * The default minimum length is <code>3</code>
     */
    private static final int DEFAULT_MIN_LENGTH = 3;
    private final LookupAttribute lookupAtt = addAttribute(LookupAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private int minLength;

    /**
     * TokenFilter only marks tokens to be looked up with equals or more as 
     * {@link #DEFAULT_MIN_LENGTH} characters
     * @param input
     */
    public WordLengthLookupFilter(TokenStream input) {
        this(input,null);
    }
    /**
     * TokenFilter only marks tokens to be looked up with equals or more characters
     * as the parsed minimum.
     * @param input the TokenStream to consume tokens from
     * @param minLength The minimum length to lookup a Token. <code>null</code>
     * or &lt;= 0 to use the #DEFAULT_MIN_LENGTH
     */
    public WordLengthLookupFilter(TokenStream input, Integer minLength) {
        super(input);
        if(minLength == null || minLength <= 0){
            this.minLength = DEFAULT_MIN_LENGTH;
        } else {
            this.minLength = minLength;
        }
    }

    @Override
    public boolean incrementToken() throws IOException {
        if(input.incrementToken()){
            int size = offsetAtt.endOffset() - offsetAtt.startOffset();
            lookupAtt.setLookup(size >= minLength);
            return true;
        } else {
            return false;
        }
    }

}
