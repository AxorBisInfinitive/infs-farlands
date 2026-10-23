package com.inf.farlands.terrain.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.inf.farlands.InfsFarlands;

/**
 * 一族系统的类型化注册表。
 *
 * <p>只存实现类不存实例：系统实例是 per-level 的，在 ServerLevel 构造时按 id 反射新建，所以同一个
 * 类会被多次实例化，类本身必须无可变共享状态。
 *
 * <p>任意线程都可登记。读与写共用 entries 自身的监视器，查表与列举因此看不到登记中间态，列举返回
 * 快照副本。单一构造器是反射取数的前提：每类恰好一个构造器时 getDeclaredConstructors()[0] 无歧义。
 */
public final class SystemRegistry<T> {

    private final Class<T> familyType;
    private final Map<SystemId, Class<? extends T>> entries = new LinkedHashMap<>();

    SystemRegistry(Class<T> familyType) {
        this.familyType = familyType;
    }

    /** 登记一个实现类。类必须实现本族接口、必须声明参数表，id 不得重复。 */
    public void register(SystemId id, Class<? extends T> type) {
        if (type == null || !this.familyType.isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                    "System %s does not implement %s".formatted(id, this.familyType.getName()));
        }
        // 注册期就要求声明参数表：没有 @SystemDefaultParams 的实现类不再退化成自由文本框，而是根本不收。
        SystemParams.requireDeclared(type);
        synchronized (this.entries) {
            if (this.entries.putIfAbsent(id, type) != null) {
                throw new IllegalStateException("Duplicate system id: " + id);
            }
        }
        InfsFarlands.LOGGER.info("Registered system {} -> {}", id, type.getName());
    }

    /** 按 id 取实现类。未注册抛 IllegalStateException。 */
    public Class<? extends T> classOf(SystemId id) {
        Class<? extends T> type = lookup(id);
        if (type == null) {
            throw new IllegalStateException("Unregistered system id: " + id);
        }
        return type;
    }

    public boolean contains(SystemId id) {
        synchronized (this.entries) {
            return this.entries.containsKey(id);
        }
    }

    /** 登记顺序的快照副本，改不动内部。顺序只保证与登记次序一致，不作其它约定。 */
    public List<SystemId> ids() {
        synchronized (this.entries) {
            return List.copyOf(this.entries.keySet());
        }
    }

    /**
     * 按 id 新建实例，并把 args 冻结。未注册抛 IllegalArgumentException；反射失败包装成
     * IllegalStateException 并带上类与 id。
     */
    public T newInstance(SystemId id, SystemArgs args) {
        Class<? extends T> type = lookup(id);
        if (type == null) {
            throw new IllegalArgumentException("Unregistered system id: " + id);
        }
        T instance = instantiate(id, type, args);
        args.freeze();
        return instance;
    }

    private Class<? extends T> lookup(SystemId id) {
        synchronized (this.entries) {
            return this.entries.get(id);
        }
    }

    private T instantiate(SystemId id, Class<? extends T> type, SystemArgs args) {
        try {
            return type.cast(type.getDeclaredConstructors()[0].newInstance(args));
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Failed to instantiate system %s (%s)".formatted(id, type.getName()), e);
        }
    }
}
