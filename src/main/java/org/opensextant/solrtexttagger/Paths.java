package org.opensextant.solrtexttagger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.util.IntsRef;

/**
 * Represents the Paths through the directed acyclic graph represented by 
 * a Solr {@link TokenStream}.
 * 
 * @author Rupert Westenthaler
 *
 */
class Paths {
  /**
   * List with the paths
   */
  protected final List<Path> paths;
  /**
   * Active paths are those that do accept new tokens at the current {@link #pos}
   */
  protected final List<Path> active;
  /**
   * The (start) position of the last added term
   */
  private int pos = -1;
  
  public Paths(){
    paths = new ArrayList<Path>();
    active = new ArrayList<Path>();
  }
    
  public Paths(int initialPathCapacity){
    paths = new ArrayList<Path>(initialPathCapacity);
    active = new ArrayList<Path>(initialPathCapacity);
  }
  
  public void addTerm(int termId, int start, int length){
    final int end = start + length;
    if(start != pos){ //first token of a new position
      updatePos(start);
      if(pos == 0){ //create a new path and add the term
        new Path().add(termId, end);
      } else { //add the parsed token to all active
        for(Path path : active){
          path.add(termId, end);
        }
      }
    } else { //alternate token for the same position
      //clone active paths and set last element of the close to the alternate
      for(Path path : active){
        path.clone().set(termId, end);
      }
    }
  }

  /**
   * Getter for the {@link IntsRef}s of the created paths
   * @return the {@link IntsRef}s for the paths
   */
  public Collection<IntsRef> getIntRefs(){
    List<IntsRef> refs = new ArrayList<IntsRef>(paths.size());
    for(Path path : paths){
      refs.add(path.getPath());
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
      for(Path path : paths){
        if(path.pEnd == pos){
          active.add(path);
        }
      }
    }
  }
  
  /**
   * Private class that represents a path.
   * @author Rupert Westenthaler
   */
  class Path implements Cloneable {
      
    /**
     * The path
     */
    private final IntsRef path;
    /**
     * the current end position of the path.
     */
    private int pEnd;

    /**
     * Creates a new (empty) path and adds it to {@link Paths#paths}
     */
    Path(){
        this(new IntsRef(8),0);
    }
    /**
     * Creates a new path based on existing data and adds it to 
     * {@link Paths#paths}. For internal use only. Use {@link #clone()} instead.
     * @param path
     * @param pEnd
     * @see #clone()
     */
    private Path(IntsRef path, int pEnd){
      this.path = path;
      this.pEnd = pEnd;
      //register the path in the collection
      paths.add(this);
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
     * Clones the path and adds the clone with {@link Paths#paths}
     */
    @Override
    public Path clone(){
      return new Path(path.clone(),pEnd);
    }
  }
  /**
   * Sorts {@link Path}s based on their
   */
  public static Comparator<Path> PATH_LENGTH_COMPARATOR = new Comparator<Path>() {

    @Override
    public int compare(Path p1, Path p2) {
      return p1.pEnd < p2.pEnd ? -1 : p1.pEnd == p2.pEnd ? 0 : 1;
    }};
    
  }