package de.muffin.universalprotect;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Kleiner Scheduler: Hauptthread-Aufgaben (Tick-basiert) und asynchrone Aufgaben. */
public final class Sched {
    private Sched() {}

    /** Abbrechbare Aufgabe. */
    public interface Handle { void cancel(); }

    private static final class Task {
        final Runnable run;
        long due;
        final long period; // 0 = einmalig
        volatile boolean cancelled;
        Task(Runnable run, long due, long period) { this.run = run; this.due = due; this.period = period; }
    }

    private static final ConcurrentLinkedQueue<Task> incoming = new ConcurrentLinkedQueue<>();
    private static final List<Task> tasks = new ArrayList<>();
    private static long tick;
    private static volatile ScheduledExecutorService async;

    public static void init() {
        async = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "UniversalProtect-Async");
            t.setDaemon(true);
            return t;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tick++;
            Task t;
            while ((t = incoming.poll()) != null) { t.due += tick; tasks.add(t); }
            for (int i = 0; i < tasks.size(); i++) {
                Task k = tasks.get(i);
                if (k.cancelled) { tasks.remove(i--); continue; }
                if (k.due > tick) continue;
                try { k.run.run(); } catch (Throwable ex) { ex.printStackTrace(); }
                if (k.period > 0) k.due = tick + k.period; else { tasks.remove(i--); }
            }
        });
    }

    public static void shutdown() {
        ScheduledExecutorService a = async;
        if (a != null) a.shutdownNow();
        incoming.clear();
        tasks.clear();
    }

    /** Auf dem Hauptthread ausführen (sofort, wenn schon dort). */
    public static void main(Runnable r) {
        var server = Mc.serverOrNull();
        if (server == null) { r.run(); return; }
        if (server.isSameThread()) r.run(); else server.execute(r);
    }

    /** In {@code ticks} Ticks auf dem Hauptthread. */
    public static Handle later(long ticks, Runnable r) {
        Task t = new Task(r, Math.max(1, ticks), 0);
        incoming.add(t);
        return () -> t.cancelled = true;
    }

    /** Jeden Tick (alle {@code period} Ticks) auf dem Hauptthread. */
    public static Handle timer(long delay, long period, Runnable r) {
        Task t = new Task(r, Math.max(1, delay), Math.max(1, period));
        incoming.add(t);
        return () -> t.cancelled = true;
    }

    public static void asyncTimer(long delayTicks, long periodTicks, Runnable r) {
        ScheduledExecutorService a = async;
        if (a != null) a.scheduleAtFixedRate(() -> { try { r.run(); } catch (Throwable t) { t.printStackTrace(); } }, delayTicks * 50, periodTicks * 50, TimeUnit.MILLISECONDS);
    }

    public static void asyncLater(long delayTicks, Runnable r) {
        ScheduledExecutorService a = async;
        if (a != null) a.schedule(() -> { try { r.run(); } catch (Throwable t) { t.printStackTrace(); } }, delayTicks * 50, TimeUnit.MILLISECONDS);
    }
}
