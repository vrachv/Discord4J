package sx.blah.discord.handle.impl.events.shard;

import sx.blah.discord.api.IShard;
import sx.blah.discord.api.internal.ReconnectManager;

/**
 * Fired when a reconnect attempt for a shard fails.
 * Note: This does not necessarily mean that the shard will be abandoned. This is fired for every failed <b>attempt</b>.
 * Use {@link #isShardAbandoned()} to determine if the shard will be abandoned.
 */
public class ReconnectFailureEvent extends ShardEvent {

	protected final int curAttempt;
	protected final int maxAttempts;

	public ReconnectFailureEvent(IShard shard, int curAttempt, int maxAttempts) {
		super(shard);
		this.curAttempt = curAttempt;
		this.maxAttempts = maxAttempts;
	}

	/**
	 * Gets the attempt the {@link ReconnectManager} failed on.
	 *
	 * @return The current attempt.
	 */
	public int getCurrentAttempt() {
		return curAttempt;
	}
	
	/**
	 * This returns whether the shard will be abandoned (no further reconnects will be attempted).
	 *
	 * @return True if shard will be abandoned, false if otherwise.
	 */
	public boolean isShardAbandoned() {
		return curAttempt > maxAttempts;
	}
}
