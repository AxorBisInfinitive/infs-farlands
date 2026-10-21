package com.inf.farlands.terrain.registry;

import net.minecraft.network.chat.Component;

/**
 * 参数解析失败，供界面显示的那条错因随异常一起带走。
 *
 * <p>为什么同时带组件与一句英文串：界面要的是译文，而 {@code Component.translatable} 是惰性的，
 * 构造时不查语言表，所以可以在抛出处就把它造好；日志与异常穿透要的是一句稳定的可读串，若只带组件，
 * {@code getMessage} 就只能给出当前语言甚至键名，日志会随语言变。
 *
 * <p>继承 {@link RuntimeException}：解析器的既有调用点都按它捕获，换成受检异常会把签名一路改下去。
 */
public final class SystemArgProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 给界面显示的那条错因。组件不可序列化，标 transient 以免抛异常本身无法序列化。 */
    private final transient Component problem;

    public SystemArgProblem(String message, Component problem) {
        super(message);
        this.problem = problem;
    }

    public SystemArgProblem(String message, Component problem, Throwable cause) {
        super(message, cause);
        this.problem = problem;
    }

    /** 界面该显示的那条错因。 */
    public Component problem() {
        return this.problem;
    }
}
