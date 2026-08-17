package com.rover.common.constants;

/** Rover 套件内可独立部署和管理的组件。 */
public enum RoverComponent {

    GATEWAY("gateway", "Gateway"),
    NAMESERVER("nameserver", "Nameserver");

    private final String id;
    private final String displayName;

    RoverComponent(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static RoverComponent from(String raw) {
        for (RoverComponent component : values()) {
            if (component.id.equalsIgnoreCase(raw)) {
                return component;
            }
        }
        throw new IllegalArgumentException("未知组件: " + raw);
    }
}
