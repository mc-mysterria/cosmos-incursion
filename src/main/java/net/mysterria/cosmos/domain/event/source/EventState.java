package net.mysterria.cosmos.domain.event.source;

public enum EventState {
    /**
     * No event active, checking trigger conditions
     */
    IDLE,

    /**
     * Event triggered, countdown in progress, zones being created
     */
    STARTING,

    /**
     * Event running, all mechanics active
     */
    ACTIVE,

    /**
     * Event concluding, cleanup in progress
     */
    ENDING
}
