package org.dynmap.spout.permissions;

import org.spout.api.command.CommandSource;

/**
 * Use spout permissions API
 */
public class PermissionProvider {
    public PermissionProvider() {}
    
    public boolean has(CommandSource source, String permission) {
        return source.hasPermission(permission);
    }
}
