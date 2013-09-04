package org.opensextant.solrtexttagger;

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.IntsRef;

/**
 * Builds phrases from {@link Token}s in {@link TokenStream}. This works by
 * evaluating {@link PositionIncrementAttribute} and 
 * {@link PositionLengthAttribute}. Integer termIds are used represent words 
 * (the tokens provided by the stream) and {@link IntsRef} is used to
 * represent single phrases.<p>
 * {@link PhraseBuilder} instance can be reused by calling {@link #reset()}.
 * However an instance MUST NOT be used by multiple threads.
 * 
 * @author Rupert Westenthaler
 *
 */
class PhraseBuilder {
  /**
   * List with the paths
   */
  protected final List<Phrase> phrases;
  /**
   * Active paths are those that do accept new tokens at the current {@link #pos}
   */
  protected final List<Phrase> active;
  /**
   * The (start) position of the last added term
   */
  private int pos = -1;
  
  /** 
   * Creates a new PhraseBuilder instance. Note that this instance should be
   * reused by calling {@link #reset()}.
   * @see #reset()
   **/
  public PhraseBuilder(){
    phrases = new ArrayList<Phrase>();
    active = new ArrayList<Phrase>();
  }
  /**
   * Creates a new PhraseBuilder instance with the initial capacity. 
   * Note that this instance should be reused by calling {@link #reset()}.
   * @see #reset()
   */
  public PhraseBuilder(int initialPathCapacity){
    phrases = new ArrayList<Phrase>(initialPathCapacity);
    active = new ArrayList<Phrase>(initialPathCapacity);
  }
  
  public void addTerm(int termId, int start, int length){
    final int end = start + length;
    if(start == 0) { //for the first position always create new paths
      new Phrase().add(termId, end);
    } else {
      if (start != pos) { //first token of a new position
        updatePos(start);
        for (Phrase path : active) {
          path.add(termId, end);
        }
      } else { //alternate token for the same position
        //clone active paths and set last element of the close to the alternate
        for (Phrase path : active) {
          path.clone().set(termId, end);
        }
      }
    }
  }

  /**
   * Getter for the {@link IntsRef}s of the created paths
   * @return the {@link IntsRef}s for the paths
   */
  public IntsRef[] getIntRefs(){
    int size = phrases.size();
    IntsRef[] refs = new IntsRef[phrases.size()];
    for (int i = 0; i < size; i++) {
      refs[i] = phrases.get(i).getPath();
    }
    return refs;
  }

  /**
   * @param newPos
   */
  private void updatePos(int start) {
    assert start > pos; //the new position MUST BE greater as the current
    pos = start;
    if(pos > 0){
      active.clear();
      for (Phrase path : phrases) {
        if (path.pEnd == pos) {
          active.add(path);
        }
      }
    }
  }
  
  /**
   * Resets the Paths instance to its initial state. This allows to reuse
   * instances. {@link IntsRef}s previously returned by {@link #getIntRefs()}
   * will not be affected by calling this. 
   */
  public void reset(){
      phrases.clear();
      active.clear();
      pos = -1;
  }
  
  /**
   * Private class used to build up a single phrase. An {@link IntsRef} is used
   * to build up the phrase. Words are represented by the integer termId.
   * @author Rupert Westenthaler
   */
  class Phrase implements Cloneable {
      
    /**
     * The path
     */
    private final IntsRef path;
    /**
     * the current end position of the path.
     */
    private int pEnd;

    /**
     * Creates a new (empty) path and adds it to {@link PhraseBuilder#phrases}
     */
    Phrase(){
        this(new IntsRef(8),0);
    }
    /**
     * Creates a new path based on existing data and adds it to 
     * {@link PhraseBuilder#phrases}. For internal use only. Use {@link #clone()} instead.
     * @param path
     * @param pEnd
     * @see #clone()
     */
    private Phrase(IntsRef path, int pEnd){
      this.path = path;
      this.pEnd = pEnd;
      //register the path in the collection
      phrases.add(this);
    }
    /**
     * Adds a term to the {@link IntsRef} representing the path
     * @param termId the termId to add
     * @param tEnd the end position of the term
     */
    void add(int termId, int tEnd){
      assert tEnd > pEnd; //the added term MUST be at least one token long
      path.grow(++path.length); //add an element to the path
      set(termId, tEnd); //set the last element
    }
    /**
     * Sets the term as last element of the {@link IntsRef} representing the path
     * @param termId the termId to set
     * @param tEnd the end position of the term
     */
    void set(int termId, int tEnd){
        path.ints[path.offset + path.length - 1] = termId;
        pEnd = tEnd; //update the end
    }
    /**
     * The {@link IntsRef} representing this path
     * @return
     */
    public IntsRef getPath() {
        return path;
    }
    /**
     * The end position of this path
     * @return
     */
    public int getEnd() {
      return pEnd;
    }
    /**
     * Clones the path and adds the clone with {@link PhraseBuilder#phrases}
     */
    @Override
    public Phrase clone(){
      //clone the array as IntsRef#clone() does not!
      int[] clone = new int[path.ints.length-path.offset]; //use same capacity
      System.arraycopy(path.ints, path.offset, clone, 0, path.length); //copy data
      return new Phrase(new IntsRef(clone,0,path.length),pEnd);
    }
  }
}