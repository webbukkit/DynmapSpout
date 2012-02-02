package org.dynmap.spout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.spout.api.event.Event;
import org.spout.api.event.EventExecutor;
import org.spout.api.event.Order;
import org.spout.api.exception.EventException;
import org.spout.api.plugin.Plugin;

public class SpoutEventProcessor {
    private Plugin plugin;

    private static class Executor implements EventExecutor {
        List<EventExecutor> lst = new ArrayList<EventExecutor>();

        public void execute(Event event) throws EventException {
            if(event.isCancelled())
                return;
            int sz = lst.size();
            for(int i = 0; i < sz; i++) {
                lst.get(i).execute(event);
            }
        }
    }
    private HashMap<Class<? extends Event>, Executor> event_handlers = new HashMap<Class<? extends Event>, Executor>();

    public SpoutEventProcessor(Plugin plugin) {
        this.plugin = plugin;
    }
    
    public void cleanup() {
        /* Clean up all registered handlers */
        for(Executor exec : event_handlers.values()) {
            exec.lst.clear(); /* Empty list - we use presence of list to remember that we've registered with Bukkit */
        }
        plugin = null;
    }
    
    /**
     * Register event listener - this will be cleaned up properly on a /dynmap reload, unlike
     * registering with Spout directly
     */
    public void registerEvent(Class<? extends Event> type, EventExecutor listener) {
        Executor x = event_handlers.get(type);
        if(x == null) {
            x = new Executor();
            event_handlers.put(type, x);
            plugin.getGame().getEventManager().registerEvent(type, Order.MONITOR, x, plugin);
        }
        x.lst.add(listener);
    }
}
