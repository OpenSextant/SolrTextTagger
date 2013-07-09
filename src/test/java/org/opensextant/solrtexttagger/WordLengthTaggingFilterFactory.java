package org.opensextant.solrtexttagger;

import java.util.Map;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.util.TokenFilterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WordLengthTaggingFilterFactory extends TokenFilterFactory {

    private final Logger log = LoggerFactory.getLogger(WordLengthTaggingFilterFactory.class);
    
    public static final String MIN_LENGTH = "minLength";
    
    private final Integer minLength;

    public WordLengthTaggingFilterFactory(Map<String,String> args) {
        super(args);
        int minLength = -1;
        Object value = args.get(MIN_LENGTH);
        if(value instanceof Number){
            minLength = ((Number)value).intValue();
        } else if(value != null){
            try{
                minLength = Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                log.warn("Unable to parse minLength from value 'minLength=\"{}\"'",value);
                
            }
        }
        if(minLength <= 0){
            log.info("use default minLength={}", WordLengthTaggingFilter.DEFAULT_MIN_LENGTH);
            this.minLength = null;
        } else {
            log.info("set minLength={}", minLength);
            this.minLength = Integer.valueOf(minLength);
        }
    }

    @Override
    public TokenStream create(TokenStream input) {
        return new WordLengthTaggingFilter(input, minLength);
    }

}
