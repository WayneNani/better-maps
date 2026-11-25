package app.organicmaps.transit.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Vertical layout of the connection sheet that turns vertical drags into a resize of its body (see
 * {@link DragListener}). Drags on the header, the handle and the time grid resize the sheet. In the scrolling list,
 * dragging up first enlarges the sheet before the list scrolls, and dragging down at the top of the list shrinks it,
 * like a Material bottom sheet.
 * <p>
 * The sheet sits at the bottom of the screen and grows upwards, so it moves under the finger: all distances are taken
 * from raw screen coordinates.
 * <p>
 * A child that keeps the gesture ({@link #keepGestureForChild()}, e.g. a ride dragged in the time grid) gets it to
 * the end: the sheet does not start a drag then.
 */
public class TransitSheetLayout extends LinearLayout
{
  /** Resizes the body of the sheet. */
  public interface DragListener
  {
    /** @return false if the sheet cannot be dragged now (e.g. nothing is shown). */
    boolean canDrag();

    /** @return true if the body can become higher (dragging up enlarges it rather than scrolling the list). */
    boolean canGrow();

    /** @return true if the body can become lower. */
    boolean canShrink();

    /** A drag starts. */
    void onDragStart();

    /** Grows (positive) or shrinks (negative) the body by {@code delta} pixels. */
    void onDrag(int delta);

    /** @param velocity pixels per second, positive downwards. */
    void onDragEnd(float velocity);
  }

  private enum Mode
  {
    UNDECIDED,
    DRAG,
    CHILD
  }

  private final int mTouchSlop;
  private final float mFlingVelocity;
  @Nullable
  private DragListener mListener;
  @Nullable
  private View mScrollingChild;
  @NonNull
  private Mode mMode = Mode.CHILD;
  private boolean mInScrollingChild;
  // A child asked to keep the gesture.
  private boolean mChildKeeps;
  private boolean mDragStarted;
  private float mDownX;
  private float mDownY;
  private float mLastY;
  // Fraction of a pixel left over from the last move.
  private float mRemainder;
  @Nullable
  private VelocityTracker mVelocity;
  private final Rect mRect = new Rect();

  public TransitSheetLayout(@NonNull Context context)
  {
    this(context, null);
  }

  public TransitSheetLayout(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    final ViewConfiguration config = ViewConfiguration.get(context);
    mTouchSlop = config.getScaledTouchSlop();
    // A deliberate flick, as for the bottom sheets of Material components.
    mFlingVelocity = config.getScaledMinimumFlingVelocity() * 8f;
  }

  public void setDragListener(@Nullable DragListener listener)
  {
    mListener = listener;
  }

  /** The vertically scrolling list in the body; drags in it scroll it unless the sheet should move first. */
  public void setScrollingChild(@Nullable View child)
  {
    mScrollingChild = child;
  }

  /** Speed above which a drag moves on to the next state. */
  public float getFlingVelocity()
  {
    return mFlingVelocity;
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent ev)
  {
    final DragListener listener = mListener;
    if (listener == null)
      return super.dispatchTouchEvent(ev);

    final int action = ev.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN)
    {
      mMode = listener.canDrag() ? Mode.UNDECIDED : Mode.CHILD;
      mChildKeeps = false;
      mDownX = ev.getRawX();
      mDownY = ev.getRawY();
      mLastY = mDownY;
      mRemainder = 0;
      mInScrollingChild = isInScrollingChild(ev);
      if (mVelocity != null)
        mVelocity.clear();
    }
    trackVelocity(ev);

    if (action == MotionEvent.ACTION_MOVE)
    {
      final float dx = ev.getRawX() - mDownX;
      final float dy = ev.getRawY() - mDownY;
      if (mMode == Mode.UNDECIDED && Math.max(Math.abs(dx), Math.abs(dy)) > mTouchSlop)
        mMode = Math.abs(dy) > Math.abs(dx) && shouldDrag(listener, dy) ? Mode.DRAG : Mode.CHILD;
      else if (mMode == Mode.CHILD && !mChildKeeps && mInScrollingChild && ev.getRawY() > mLastY
               && listener.canShrink()
               && mScrollingChild != null && !mScrollingChild.canScrollVertically(-1) && Math.abs(dy) > Math.abs(dx))
      {
        // The list was scrolled back to its top and the finger goes on downwards: hand over to the sheet.
        mMode = Mode.DRAG;
      }
      if (mMode == Mode.DRAG)
      {
        if (!mDragStarted)
          startDrag(ev, listener);
        drag(ev.getRawY(), listener);
        return true;
      }
      mLastY = ev.getRawY();
    }
    else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
    {
      final boolean dragged = mMode == Mode.DRAG && mDragStarted;
      mMode = Mode.CHILD;
      mDragStarted = false;
      if (dragged)
      {
        float velocity = 0;
        if (mVelocity != null && action == MotionEvent.ACTION_UP)
        {
          mVelocity.computeCurrentVelocity(1000);
          velocity = mVelocity.getYVelocity();
        }
        listener.onDragEnd(velocity);
        return true;
      }
    }
    else if (mMode == Mode.DRAG)
      return true;
    final boolean handled = super.dispatchTouchEvent(ev);
    // Keep the gesture even if it starts where no child takes it (e.g. on the handle), so that it can be a drag.
    return handled || (action == MotionEvent.ACTION_DOWN && mMode == Mode.UNDECIDED);
  }

  /**
   * The current gesture belongs to a child (e.g. a ride lifted in the time grid): the sheet does not start a drag
   * until the finger is lifted. Unlike {@link #requestDisallowInterceptTouchEvent}, which the list also calls while it
   * scrolls, this keeps the sheet out of the gesture even at the top of the list.
   */
  public void keepGestureForChild()
  {
    // Too late once the sheet is dragged: it took the gesture from the children already.
    if (mMode == Mode.DRAG)
      return;
    mMode = Mode.CHILD;
    mChildKeeps = true;
  }

  /** True if any ancestor of the view is a sheet, then told to {@link #keepGestureForChild()}. */
  static boolean keepGesture(@NonNull View child)
  {
    boolean found = false;
    for (ViewParent parent = child.getParent(); parent != null; parent = parent.getParent())
      if (parent instanceof TransitSheetLayout)
      {
        ((TransitSheetLayout) parent).keepGestureForChild();
        found = true;
      }
    return found;
  }

  private boolean shouldDrag(@NonNull DragListener listener, float dy)
  {
    final View child = mScrollingChild;
    if (!mInScrollingChild || child == null)
      return dy < 0 ? listener.canGrow() : listener.canShrink();
    // In the list: up enlarges the sheet while the list has more to show, down shrinks it at the top of the list.
    if (dy < 0)
      return listener.canGrow() && child.canScrollVertically(1);
    return listener.canShrink() && !child.canScrollVertically(-1);
  }

  private void startDrag(@NonNull MotionEvent ev, @NonNull DragListener listener)
  {
    mDragStarted = true;
    // The children (e.g. a pressed button or the list) lose the gesture.
    final MotionEvent cancel = MotionEvent.obtain(ev);
    cancel.setAction(MotionEvent.ACTION_CANCEL);
    super.dispatchTouchEvent(cancel);
    cancel.recycle();
    if (getParent() != null)
      getParent().requestDisallowInterceptTouchEvent(true);
    listener.onDragStart();
  }

  private void drag(float rawY, @NonNull DragListener listener)
  {
    final float delta = mLastY - rawY + mRemainder;
    final int pixels = (int) delta;
    mRemainder = delta - pixels;
    mLastY = rawY;
    if (pixels != 0)
      listener.onDrag(pixels);
  }

  private boolean isInScrollingChild(@NonNull MotionEvent ev)
  {
    final View child = mScrollingChild;
    if (child == null || child.getVisibility() != View.VISIBLE || !child.getGlobalVisibleRect(mRect))
      return false;
    final int[] offset = new int[2];
    child.getRootView().getLocationOnScreen(offset);
    mRect.offset(offset[0], offset[1]);
    return mRect.contains((int) ev.getRawX(), (int) ev.getRawY());
  }

  private void trackVelocity(@NonNull MotionEvent ev)
  {
    if (mVelocity == null)
      mVelocity = VelocityTracker.obtain();
    final MotionEvent raw = MotionEvent.obtain(ev);
    raw.setLocation(ev.getRawX(), ev.getRawY());
    mVelocity.addMovement(raw);
    raw.recycle();
  }

  @Override
  protected void onDetachedFromWindow()
  {
    if (mVelocity != null)
    {
      mVelocity.recycle();
      mVelocity = null;
    }
    super.onDetachedFromWindow();
  }
}
