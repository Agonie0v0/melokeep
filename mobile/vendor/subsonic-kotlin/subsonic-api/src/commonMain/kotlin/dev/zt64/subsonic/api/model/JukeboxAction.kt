package dev.zt64.subsonic.api.model

import kotlinx.serialization.Serializable

/**
 * Jukebox control action
 */
@Serializable
public sealed class JukeboxAction {
    internal sealed interface ActionId {
        val id: Long
    }

    /**
     * Status
     */
    public data object Status : JukeboxAction()

    /**
     * Start
     */
    public data object Start : JukeboxAction()

    /**
     * Stop
     */
    public data object Stop : JukeboxAction()

    /**
     * Shuffle
     */
    public data object Shuffle : JukeboxAction()

    /**
     * Clear
     */
    public data object Clear : JukeboxAction()

    /**
     * Add
     *
     * @property id
     */
    @Serializable
    public data class Add(override val id: Long) : JukeboxAction(), ActionId

    /**
     * Set
     *
     * @property id
     */
    @Serializable
    public data class Set(override val id: Long) : JukeboxAction(), ActionId

    /**
     * Remove
     *
     * @property index
     */
    @Serializable
    public data class Remove(val index: Int) : JukeboxAction()

    /**
     * Skip
     *
     * @property index
     * @property offset
     */
    @Serializable
    public data class Skip(val index: Int, val offset: Int = 0) : JukeboxAction()

    /**
     * Set gain
     *
     * @property gain
     */
    @Serializable
    public data class SetGain(val gain: Float) : JukeboxAction()
}