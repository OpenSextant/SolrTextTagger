package org.opensextant.solrtexttagger;

import org.apache.lucene.util.AttributeImpl;

/**
 * Implementation of the {@link TaggingAttribute}
 *  
 * @author Rupert Westenthaler
 *
 */
public class TaggingAttributeImpl extends AttributeImpl implements TaggingAttribute {

    /**
     * the private field initialised with {@link TaggingAttribute#DEFAULT_LOOKUP}
     */
    private boolean lookup = TaggingAttribute.DEFAULT_LOOKUP;
    
    /*
     * (non-Javadoc)
     * @see org.opensextant.solrtexttagger.LookupAttribute#isLookup()
     */
    @Override
    public boolean isTaggable() {
        return lookup;
    }
    /*
     * (non-Javadoc)
     * @see org.opensextant.solrtexttagger.LookupAttribute#setLookup(boolean)
     */
    @Override
    public void setTaggable(boolean lookup) {
        this.lookup = lookup;
    }
    /*
     * (non-Javadoc)
     * @see org.apache.lucene.util.AttributeImpl#clear()
     */
    @Override
    public void clear() {
        lookup = DEFAULT_LOOKUP;
        
    }
    /*
     * (non-Javadoc)
     * @see org.apache.lucene.util.AttributeImpl#copyTo(org.apache.lucene.util.AttributeImpl)
     */
    @Override
    public void copyTo(AttributeImpl target) {
        ((TaggingAttribute)target).setTaggable(lookup);
    }
    
}
