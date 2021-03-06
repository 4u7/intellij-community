// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.find.impl.livePreview;


import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindResult;
import com.intellij.find.FindUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.FutureResult;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Stack;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.PatternSyntaxException;

public class SearchResults implements DocumentListener {

  public int getStamp() {
    return ++myStamp;
  }

  @Override
  public void beforeDocumentChange(@NotNull DocumentEvent event) {
    myCursorPositions.clear();
  }

  public enum Direction {UP, DOWN}


  private final List<SearchResultsListener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();

  @Nullable private FindResult myCursor;

  @NotNull
  private List<FindResult> myOccurrences = new ArrayList<>();

  private final Set<RangeMarker> myExcluded = new HashSet<>();

  @NotNull
  private final Editor myEditor;
  private final Project myProject;
  private FindModel myFindModel;

  private int myMatchesLimit = 100;

  private boolean myNotFoundState;

  private boolean myDisposed;

  private int myStamp;

  private int myLastUpdatedStamp = -1;
  private long myDocumentTimestamp;

  private final Stack<Pair<FindModel, FindResult>> myCursorPositions = new Stack<>();

  private final SelectionManager mySelectionManager;

  public SearchResults(@NotNull Editor editor, Project project) {
    myEditor = editor;
    myProject = project;
    myEditor.getDocument().addDocumentListener(this);
    mySelectionManager = new SelectionManager(this); // important to initialize last for accessing other fields
  }

  public void setNotFoundState(boolean isForward) {
    myNotFoundState = true;
    FindModel findModel = new FindModel();
    findModel.copyFrom(myFindModel);
    findModel.setForward(isForward);
    FindUtil.processNotFound(myEditor, findModel.getStringToFind(), findModel, getProject());
  }

  public int getMatchesCount() {
    return myOccurrences.size();
  }

  public boolean hasMatches() {
    return !getOccurrences().isEmpty();
  }

  public FindModel getFindModel() {
    return myFindModel;
  }

  public boolean isExcluded(FindResult occurrence) {
    for (RangeMarker rangeMarker : myExcluded) {
      if (TextRange.areSegmentsEqual(rangeMarker, occurrence)) {
        return true;
      }
    }
    return false;
  }

  public void exclude(FindResult occurrence) {
    boolean include = false;
    for (RangeMarker rangeMarker : myExcluded) {
      if (TextRange.areSegmentsEqual(rangeMarker, occurrence)) {
        myExcluded.remove(rangeMarker);
        rangeMarker.dispose();
        include = true;
        break;
      }
    }
    if (!include) {
      myExcluded.add(myEditor.getDocument().createRangeMarker(occurrence.getStartOffset(), occurrence.getEndOffset(), true));
    }
    notifyChanged();
  }

  public Set<RangeMarker> getExcluded() {
    return myExcluded;
  }

  public interface SearchResultsListener {

    void searchResultsUpdated(@NotNull SearchResults sr);
    void cursorMoved();

    void updateFinished();
  }
  public void addListener(@NotNull SearchResultsListener srl) {
    myListeners.add(srl);
  }

  public void removeListener(@NotNull SearchResultsListener srl) {
    myListeners.remove(srl);
  }

  public int getMatchesLimit() {
    return myMatchesLimit;
  }

  public void setMatchesLimit(int matchesLimit) {
    myMatchesLimit = matchesLimit;
  }

  @Nullable
  public FindResult getCursor() {
    return myCursor;
  }

  @NotNull
  public List<FindResult> getOccurrences() {
    return myOccurrences;
  }

  @Nullable
  public Project getProject() {
    return myProject;
  }

  @NotNull
  public Editor getEditor() {
    return myEditor;
  }

  public void clear() {
    searchCompleted(new ArrayList<>(), getEditor(), null, false, null, getStamp());
  }

  ActionCallback updateThreadSafe(@NotNull FindModel findModel, final boolean toChangeSelection,
                                  @Nullable final TextRange next, final int stamp) {
    if (myDisposed) return ActionCallback.DONE;

    ActionCallback result = new ActionCallback();
    final Editor editor = getEditor();

    updatePreviousFindModel(findModel);
    final FutureResult<int[]> startsRef = new FutureResult<>();
    final FutureResult<int[]> endsRef = new FutureResult<>();
    getSelection(editor, startsRef, endsRef);

    List<FindResult> results = new ArrayList<>();
    ApplicationManager.getApplication().runReadAction(() -> {
      Project project = getProject();
      if (myDisposed || project != null && project.isDisposed()) return;
      int[] starts = new int[0];
      int[] ends = new int[0];
      try {
        starts = startsRef.get();
        ends = endsRef.get();
      }
      catch (InterruptedException | ExecutionException ignore) {
      }

      if (starts.length == 0 || findModel.isGlobal()) {
        findInRange(new TextRange(0, Integer.MAX_VALUE), editor, findModel, results);
      }
      else {
        for (int i = 0; i < starts.length; ++i) {
          findInRange(new TextRange(starts[i], ends[i]), editor, findModel, results);
        }
      }

      long documentTimeStamp = editor.getDocument().getModificationStamp();
      final Runnable searchCompletedRunnable = () -> {
        if (editor.getDocument().getModificationStamp() == documentTimeStamp) {
          searchCompleted(results, editor, findModel, toChangeSelection, next, stamp);
          result.setDone();
        }
        else {
          result.setRejected();
        }
      };

      if (ApplicationManager.getApplication().isUnitTestMode()) {
        searchCompletedRunnable.run();
      }
      else {
        UIUtil.invokeLaterIfNeeded(searchCompletedRunnable);
      }
    });
    return result;
  }

  private void updatePreviousFindModel(@NotNull FindModel model) {
    FindModel prev = FindManager.getInstance(getProject()).getPreviousFindModel();
    if (prev == null) {
      prev = new FindModel();
    }
    if (!model.getStringToFind().isEmpty()) {
      prev.copyFrom(model);
      FindManager.getInstance(getProject()).setPreviousFindModel(prev);
    }
  }

  private static void getSelection(final Editor editor, final FutureResult<int[]> starts, final FutureResult<int[]> ends) {
    if (ApplicationManager.getApplication().isDispatchThread()) {
      SelectionModel selection = editor.getSelectionModel();
      starts.set(selection.getBlockSelectionStarts());
      ends.set(selection.getBlockSelectionEnds());
    }
    else {
      try {
        SwingUtilities.invokeAndWait(() -> {
          SelectionModel selection = editor.getSelectionModel();
          starts.set(selection.getBlockSelectionStarts());
          ends.set(selection.getBlockSelectionEnds());
        });
      }
      catch (InterruptedException | InvocationTargetException ignore) {
      }
    }
  }

  private void findInRange(@NotNull TextRange range, @NotNull Editor editor, @NotNull FindModel findModel, @NotNull List<? super FindResult> results) {
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());

    // Document can change even while we're holding read lock (example case - console), so we're taking an immutable snapshot of text here
    CharSequence charSequence = editor.getDocument().getImmutableCharSequence();

    int offset = range.getStartOffset();
    int maxOffset = Math.min(range.getEndOffset(), charSequence.length());
    FindManager findManager = FindManager.getInstance(getProject());

    while (true) {
      FindResult result;
      try {
        CharSequence bombedCharSequence = StringUtil.newBombedCharSequence(charSequence, 3000);
        result = findManager.findString(bombedCharSequence, offset, findModel, virtualFile);
        ((StringUtil.BombedCharSequence)bombedCharSequence).defuse();
      }
      catch(PatternSyntaxException | ProcessCanceledException e) {
        result = null;
      }
      if (result == null || !result.isStringFound()) break;
      final int newOffset = result.getEndOffset();
      if (result.getEndOffset() > maxOffset) break;
      if (offset == newOffset) {
        if (offset < maxOffset - 1) {
          offset++;
        }
        else {
          results.add(result);
          break;
        }
      }
      else {
        offset = newOffset;
        if (offset == result.getStartOffset()) ++offset; // skip zero width result
      }
      results.add(result);
    }
  }

  public void dispose() {
    myDisposed = true;
    myEditor.getDocument().removeDocumentListener(this);
  }

  private void searchCompleted(@NotNull List<FindResult> occurrences, @NotNull Editor editor, @Nullable FindModel findModel,
                               boolean toChangeSelection, @Nullable TextRange next, int stamp) {
    if (stamp < myLastUpdatedStamp){
      return;
    }
    myLastUpdatedStamp = stamp;
    if (editor != getEditor() || myDisposed || editor.isDisposed()) {
      return;
    }
    myOccurrences = occurrences;
    final TextRange oldCursorRange = myCursor;
    Collections.sort(myOccurrences, Comparator.comparingInt(TextRange::getStartOffset));

    myFindModel = findModel;
    myDocumentTimestamp = myEditor.getDocument().getModificationStamp();
    updateCursor(oldCursorRange, next);
    updateExcluded();
    notifyChanged();
    if (myCursor == null || !myCursor.equals(oldCursorRange)) {
      if (toChangeSelection) {
        mySelectionManager.updateSelection(true, true);
      }
      notifyCursorMoved();
    }
    dumpIfNeeded();
  }

  private void dumpIfNeeded() {
    for (SearchResultsListener listener : myListeners) {
      listener.updateFinished();
    }
  }

  private void updateExcluded() {
    Set<RangeMarker> invalid = new HashSet<>();
    for (RangeMarker marker : myExcluded) {
      if (!marker.isValid()) {
        invalid.add(marker);
        marker.dispose();
      }
    }
    myExcluded.removeAll(invalid);
  }

  private void updateCursor(@Nullable TextRange oldCursorRange, @Nullable TextRange next) {
    boolean justReplaced = next != null;
    boolean toPush = true;
    if (justReplaced || (toPush = !repairCursorFromStack())) {
      if (justReplaced || !tryToRepairOldCursor(oldCursorRange)) {
        if (myFindModel != null) {
          if(oldCursorRange != null && !myFindModel.isGlobal()) {
            myCursor = firstOccurrenceAfterOffset(oldCursorRange.getEndOffset());
          }
          else {
            if (justReplaced) {
              nextOccurrence(false, next, false, true, false);
            }
            else {
              myCursor = oldCursorRange == null ? firstOccurrenceAtOrAfterCaret() : firstOccurrenceAfterCaret();
            }
          }
        }
        else {
          myCursor = null;
        }
      }
    }
    if (!justReplaced && myCursor == null && hasMatches()) {
      nextOccurrence(true, oldCursorRange, false, false, false);
    }
    if (toPush && myCursor != null){
      push();
    }
  }

  private boolean repairCursorFromStack() {
    if (myCursorPositions.size() >= 2) {
      final Pair<FindModel, FindResult> oldPosition = myCursorPositions.get(myCursorPositions.size() - 2);
      if (oldPosition.first.equals(myFindModel)) {
        FindResult newCursor;
        if ((newCursor = findOccurrenceEqualTo(oldPosition.second)) != null) {
          myCursorPositions.pop();
          myCursor = newCursor;
          return true;
        }
      }
    }
    return false;
  }

  @Nullable
  private FindResult findOccurrenceEqualTo(FindResult occurrence) {
    for (FindResult findResult : myOccurrences) {
      if (findResult.equals(occurrence)) {
        return findResult;
      }
    }
    return null;
  }

  @Nullable
  private FindResult firstOccurrenceAtOrAfterCaret() {
    int offset = getEditor().getCaretModel().getOffset();
    for (FindResult occurrence : myOccurrences) {
      if (offset <= occurrence.getEndOffset() && offset >= occurrence.getStartOffset()) {
        return occurrence;
      }
    }
    int selectionStartOffset = getEditor().getSelectionModel().getSelectionStart();
    int selectionEndOffset = getEditor().getSelectionModel().getSelectionEnd();
    for (FindResult occurrence : myOccurrences) {
      if (selectionEndOffset >= occurrence.getEndOffset() && selectionStartOffset <= occurrence.getStartOffset()) {
        return occurrence;
      }
    }
    return firstOccurrenceAfterCaret();
  }

  private void notifyChanged() {
    for (SearchResultsListener listener : myListeners) {
      listener.searchResultsUpdated(this);
    }
  }

  static boolean insideVisibleArea(Editor e, TextRange r) {
    int startOffset = r.getStartOffset();
    if (startOffset > e.getDocument().getTextLength()) return false;
    Rectangle visibleArea = e.getScrollingModel().getVisibleArea();
    Point point = e.logicalPositionToXY(e.offsetToLogicalPosition(startOffset));

    return visibleArea.contains(point);
  }

  @Nullable
  private FindResult firstOccurrenceBeforeCaret() {
    int offset = getEditor().getCaretModel().getOffset();
    return firstOccurrenceBeforeOffset(offset);
  }

  @Nullable
  private FindResult firstOccurrenceBeforeOffset(int offset) {
    for (int i = getOccurrences().size()-1; i >= 0; --i) {
      if (getOccurrences().get(i).getEndOffset() < offset) {
        return getOccurrences().get(i);
      }
    }
    return null;
  }

  @Nullable
  private FindResult firstOccurrenceAfterCaret() {
    int caret = myEditor.getCaretModel().getOffset();
    return firstOccurrenceAfterOffset(caret);
  }

  @Nullable
  private FindResult firstOccurrenceAfterOffset(int offset) {
    FindResult afterCaret = null;
    for (FindResult occurrence : getOccurrences()) {
      if (occurrence.getStartOffset() >= offset && occurrence.getEndOffset() > offset) {
        if (afterCaret == null || occurrence.getStartOffset() < afterCaret.getStartOffset() ) {
          afterCaret = occurrence;
        }
      }
    }
    return afterCaret;
  }

  private boolean tryToRepairOldCursor(@Nullable TextRange oldCursorRange) {
    if (oldCursorRange == null) return false;
    FindResult mayBeOldCursor = null;
    for (FindResult searchResult : getOccurrences()) {
      if (searchResult.intersects(oldCursorRange)) {
        mayBeOldCursor = searchResult;
      }
      if (searchResult.getStartOffset() == oldCursorRange.getStartOffset()) {
        break;
      }
    }
    if (mayBeOldCursor != null) {
      myCursor = mayBeOldCursor;
      return true;
    }
    return false;
  }

  @Nullable
  private FindResult prevOccurrence(TextRange range) {
    for (int i = getOccurrences().size() - 1; i >= 0; --i) {
      final FindResult occurrence = getOccurrences().get(i);
      if (occurrence.getEndOffset() <= range.getStartOffset())  {
          return occurrence;
      }
    }
    return null;
  }

  @Nullable
  private FindResult nextOccurrence(TextRange range) {
    for (FindResult occurrence : getOccurrences()) {
      if (occurrence.getStartOffset() >= range.getEndOffset()) {
        return occurrence;
      }
    }
    return null;
  }

  public void prevOccurrence(boolean findSelected) {
    if (findSelected) {
      if (mySelectionManager.removeCurrentSelection()) {
        myCursor = firstOccurrenceAtOrAfterCaret();
      }
      else {
        myCursor = null;
      }
      notifyCursorMoved();
    }
    else {
      if (myFindModel == null) return;
      boolean processFromTheBeginning = false;
      if (myNotFoundState) {
        myNotFoundState = false;
        processFromTheBeginning = true;
      }
      FindResult next = null;
      if (!myFindModel.isGlobal()) {
        if (myCursor != null) {
          next = prevOccurrence(myCursor);
        }
      }
      else {
        next = firstOccurrenceBeforeCaret();
      }
      if (next == null) {
        if (processFromTheBeginning) {
          if (hasMatches()) {
            next = getOccurrences().get(getOccurrences().size() - 1);
          }
        }
        else {
          setNotFoundState(false);
        }
      }

      moveCursorTo(next, false);
    }
    push();
  }

  private void push() {
    myCursorPositions.push(Pair.create(myFindModel, myCursor));
  }

  public void nextOccurrence(boolean retainOldSelection) {
    if (myFindModel == null) return;
    nextOccurrence(false, myCursor, true, false, retainOldSelection);
    push();
  }

  private void nextOccurrence(boolean processFromTheBeginning,
                              TextRange cursor,
                              boolean toNotify,
                              boolean justReplaced,
                              boolean retainOldSelection) {
    if (myNotFoundState) {
      myNotFoundState = false;
      processFromTheBeginning = true;
    }
    FindResult next;
    if ((!myFindModel.isGlobal() || justReplaced) && cursor != null) {
      next = nextOccurrence(cursor);
    }
    else {
      next = firstOccurrenceAfterCaret();
    }
    if (next == null) {
      if (processFromTheBeginning) {
        if (hasMatches()) {
          next = getOccurrences().get(0);
        }
      }
      else {
        setNotFoundState(true);
      }
    }
    if (toNotify) {
      moveCursorTo(next, retainOldSelection);
    }
    else {
      myCursor = next;
    }
  }

  public void moveCursorTo(FindResult next, boolean retainOldSelection) {
    if (next != null && !mySelectionManager.isSelected(next)) {
      retainOldSelection &= myCursor != null && mySelectionManager.isSelected(myCursor);
      myCursor = next;
      mySelectionManager.updateSelection(!retainOldSelection, false);
      notifyCursorMoved();
    }
  }

  private void notifyCursorMoved() {
    for (SearchResultsListener listener : myListeners) {
      listener.cursorMoved();
    }
  }

  public boolean isUpToDate() {
    return myDocumentTimestamp == myEditor.getDocument().getModificationStamp();
  }
}
