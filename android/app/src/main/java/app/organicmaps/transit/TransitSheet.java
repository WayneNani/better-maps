package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Logic of the draggable connection sheet in the route planner, without Android dependencies: its states, where a
 * drag ends and the summary line shown when it is collapsed.
 * <p>
 * Heights are those of the sheet's body (the connection list, the time grid or the legs of a connection); the
 * header above it is not counted.
 */
public final class TransitSheet
{
  public enum State
  {
    /** Only the handle and a summary line; the map is visible. */
    COLLAPSED,
    /** The header and the body up to a part of the screen, like the panel without dragging. */
    HALF,
    /** The body up to the top of the map. */
    EXPANDED
  }

  private TransitSheet() {}

  /**
   * The state a drag ends in.
   *
   * @param height body height when the finger was lifted.
   * @param half body height of {@link State#HALF}.
   * @param max body height of {@link State#EXPANDED}; not less than {@code half} in practice.
   * @param velocity speed of the finger in pixels per second, positive downwards (shrinking the sheet).
   * @param flingVelocity a faster finger moves on to the next state in its direction.
   */
  @NonNull
  public static State settle(int height, int half, int max, float velocity, float flingVelocity)
  {
    half = Math.min(half, max);
    // Nothing more to show above HALF: there are two states only.
    final boolean twoStates = max - half < 1;
    if (velocity > flingVelocity)
      return height > half && !twoStates ? State.HALF : State.COLLAPSED;
    if (velocity < -flingVelocity)
      return height < half || twoStates ? State.HALF : State.EXPANDED;
    if (height < half / 2)
      return State.COLLAPSED;
    if (twoStates || height < half + (max - half) / 2)
      return State.HALF;
    return State.EXPANDED;
  }

  /** Body height of a state. */
  public static int bodyHeight(@NonNull State state, int half, int max)
  {
    return switch (state)
    {
      case COLLAPSED -> 0;
      case HALF -> Math.min(half, max);
      case EXPANDED -> max;
    };
  }

  /**
   * The line shown in the collapsed sheet, e.g. "11:44 – 11:57 · 13 min · 1 transfer" for a connection or
   * "5 connections · Depart now" for the list.
   *
   * @return null if there is nothing to summarize or an error is shown (the view shows the error instead).
   */
  @Nullable
  public static String summary(@NonNull ConnectionsState state, @NonNull TransitTexts texts)
  {
    final ItineraryItem selected = state.getSelected();
    if (state.getScreen() == ConnectionsState.Screen.DETAIL && selected != null)
      return title(selected);
    if (state.getScreen() != ConnectionsState.Screen.LIST || state.getError() != null)
      return null;
    if (state.isLoading())
      return texts.searching() + " · " + state.getTimeLabel();
    if (state.getItems().isEmpty())
      return texts.noConnections();
    return texts.connections(state.getItems().size()) + " · " + state.getTimeLabel();
  }

  /** Times, duration and transfers of a connection, e.g. "11:44 – 11:57 · 13 min · 1 transfer". */
  @NonNull
  public static String title(@NonNull ItineraryItem item)
  {
    final String times = item.getDeparture() + " – " + item.getArrival() + " · " + item.getDuration();
    return item.isWalkOnly() ? times : times + " · " + item.getTransfers();
  }
}
