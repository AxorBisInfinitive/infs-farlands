package com.inf.farlands.terrain.registry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.inf.farlands.terrain.registry.SystemsData.Arg;

import net.minecraft.network.chat.Component;

/**
 * 一个系统声明的参数表，由标注了 {@link SystemDefaultParams} 的静态字段携带。
 *
 * <p>
 * 语义三分，调用方必须分清：
 * EMPTY 表示这个系统不吃参数，界面给 0 个框；
 * null，即没有带注解的字段或字段值为 null，表示没有声明，界面退化成一个自由文本框；
 * 字段形状不对是错误，直接抛，不静默当成没声明。
 *
 * <p>
 * 校验分两层。单参数的值域与语义集合写在 {@link SystemParamSpec} 上，由界面逐键执行，失败只标红那一个框。
 * 跨字段规则写在本类的 {@link Rule} 上，只在全部参数都解析成功之后执行，失败时把该规则引用的那几个参数
 * 一起标红。
 *
 * <p>
 * 两条入口同一份声明：创建世界页签与落盘的 systems.dat 都走本类，规则因此不需要写第二遍。
 */
public record SystemParams(List<SystemParamSpec> entries, List<Rule> rules) {

    public static final SystemParams EMPTY = new SystemParams(List.of(), List.of());

    public SystemParams {
        entries = List.copyOf(entries);
        rules = List.copyOf(rules);
        List<String> keys = new ArrayList<>(entries.size());
        for (SystemParamSpec spec : entries) {
            if (keys.contains(spec.key())) {
                throw new IllegalArgumentException("Duplicate system param key: " + spec.key());
            }
            keys.add(spec.key());
        }
    }

    public static SystemParams of(SystemParamSpec... specs) {
        return new SystemParams(List.of(specs), List.of());
    }

    /**
     * 一条跨字段规则。
     *
     * <p>
     * keys 是失败时要标红的参数，也是这条规则读到的全部参数。check 返回 null 表示通过，否则返回给人看的
     * 错因。求值时全部键都已确认在场，所以规则内部不必判缺失。
     *
     * <p>
     * 规则里做算术时自己收窄：参数是 int，两个 int 相加在 int 上溢出会让上界判据恒真，那条规则就形同虚设。
     */
    public record Rule(Set<String> keys, Function<SystemArgs, Component> check) {

        public Rule {
            if (keys == null || check == null) {
                throw new IllegalArgumentException("System param rule needs both keys and check");
            }
            keys = Set.copyOf(keys);
        }
    }

    /** 一条违规。keys 是涉及的参数，message 是给人看的错因，用语言键构造。 */
    public record Violation(Set<String> keys, Component message) {
    }

    /** 规则工厂，供声明处静态导入，让调用点读起来接近键与规则的配对。 */
    public static Rule rule(Set<String> keys, Function<SystemArgs, Component> check) {
        return new Rule(keys, check);
    }

    /**
     * 谓词加字面文本的便捷重载。
     *
     * <p>
     * 文本不随语言切换，界面会原样显示，所以只用于诊断或没有语言文件的第三方系统。常规写法是带语言键
     * 的那个重载。
     */
    public static Rule rule(Set<String> keys, Predicate<SystemArgs> test, String literal) {
        return new Rule(keys, args -> test.test(args) ? null : Component.literal(literal));
    }

    /**
     * 追加若干跨字段规则。声明期逐条检查，违反即抛，不留到运行时。
     *
     * <p>
     * 四条检查：键数至少两个、键都在本表里、每个键都有默认值或空串关键字、规则本身非空。
     *
     * <p>
     * 第三条是因为界面逐键解析：一个没有默认值也没被填过的参数解析失败，跨字段那一层就永远跑不到，规则
     * 看着写了实际不可达。第一条是因为单参数的检查应当写在 spec 上，那样才能逐键标红；写成单键规则只在
     * 提交时触发，同一个判据会有两种完全不同的表现。
     */
    public SystemParams withConstraint(Rule... added) {
        if (added.length == 0) {
            throw new IllegalArgumentException("farlands: withConstraint needs at least one rule");
        }
        List<Rule> merged = new ArrayList<>(this.rules);
        for (Rule rule : added) {
            checkRule(rule);
            merged.add(rule);
        }
        return new SystemParams(this.entries, merged);
    }

    private void checkRule(Rule rule) {
        if (rule.keys().size() < 2) {
            throw new IllegalArgumentException(
                    "farlands: single-param rule belongs on the param spec: " + rule.keys());
        }
        for (String key : rule.keys()) {
            SystemParamSpec spec = specOf(key);
            if (spec == null) {
                throw new IllegalArgumentException("farlands: rule references undeclared param: " + key);
            }
            if (spec.defaultValue() == null && !spec.keywords().containsKey("")) {
                throw new IllegalArgumentException(
                        "farlands: rule param needs a default or an empty keyword: " + key);
            }
        }
    }

    private SystemParamSpec specOf(String key) {
        for (SystemParamSpec spec : this.entries) {
            if (spec.key().equals(key)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 跑一遍本表声明的全部校验，返回全部违规。
     *
     * <p>
     * 次序固定：先判每个声明键都在场，缺键即报；全部在场才跑单参数检查与跨字段规则。缺键时不跑规则，
     * 因为规则用 {@link SystemArgs} 的取值方，缺键会抛而不是给出可读的错因。
     *
     * <p>
     * 不短路。同一份参数可能同时违反两条规则，一次报全比改一条撞一次省事。
     */
    public List<Violation> validate(Map<String, Arg> values) {
        List<Violation> out = new ArrayList<>();
        for (SystemParamSpec spec : this.entries) {
            if (!values.containsKey(spec.key())) {
                out.add(new Violation(Set.of(spec.key()),
                        Component.translatable("createWorld.tab.infs-farlands.param.error.missing", spec.key())));
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (SystemParamSpec spec : this.entries) {
            Component problem;
            try {
                problem = spec.violation(values.get(spec.key()));
            } catch (RuntimeException e) {
                // 值不是字符串、或字符串解析不出数：文件是旧形态或被人手改坏了。记成一条违规，
                // 让界面能标到这个参数上，而不是整族抛出去。
                out.add(new Violation(Set.of(spec.key()),
                        Component.literal(e.getMessage() == null ? e.toString() : e.getMessage())));
                continue;
            }
            if (problem != null) {
                out.add(new Violation(Set.of(spec.key()), problem));
            }
        }
        if (!out.isEmpty() || this.rules.isEmpty()) {
            return out;
        }
        SystemArgs args = SystemArgs.of(values);
        for (Rule rule : this.rules) {
            Component problem = rule.check().apply(args);
            if (problem != null) {
                out.add(new Violation(rule.keys(), problem));
            }
        }
        return out;
    }

    /**
     * 读该类的参数声明。没有带注解的字段或字段值为 null 时返回 null。带注解的字段多于一个，或字段不是
     * static final SystemParams 时抛：字段扫描顺序没有规范保证，不能取第一个了事。
     */
    public static SystemParams declaredBy(Class<?> systemClass) {
        Field declared = null;
        for (Field field : systemClass.getDeclaredFields()) {
            if (!field.isAnnotationPresent(SystemDefaultParams.class)) {
                continue;
            }
            if (declared != null) {
                throw new IllegalStateException(
                        "farlands: " + systemClass.getName() + " has more than one @SystemDefaultParams field");
            }
            declared = field;
        }
        if (declared == null) {
            return null;
        }
        if (!Modifier.isStatic(declared.getModifiers()) || !Modifier.isFinal(declared.getModifiers())
                || declared.getType() != SystemParams.class) {
            throw new IllegalStateException(
                    "farlands: " + systemClass.getName() + " @SystemDefaultParams field must be static final SystemParams");
        }
        try {
            declared.setAccessible(true);
            return (SystemParams) declared.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("farlands: failed to read param declaration of " + systemClass.getName(), e);
        }
    }
}
