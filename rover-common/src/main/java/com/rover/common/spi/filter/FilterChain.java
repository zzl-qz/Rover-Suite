package com.rover.common.spi.filter;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 定义网关过滤器链推进契约，供 Filter 继续执行下一环
 *
 * 这个接口是什么：过滤器链的推进句柄(Servlet 风格的 chain.doFilter)。
 * 核心职责：把「当前过滤器之后还有哪些过滤器」封装起来，Filter 只需调用 doFilter
 * 即可把请求交给下一个环节，链路结束时自然收尾。
 * 被谁用：Filter 实现类内部推进链路；实现方为 rover-gateway 的过滤器链执行器。
 */
public interface FilterChain {

    /**
     * 继续执行下一个过滤器。
     * 如果当前已经是最后一个，链路自然结束；
     * 如果前面已经 markCompleted，后续也不会再执行。
     *
     * @param context 请求上下文
     * @throws Exception 链路内任意过滤器抛出的异常原样上抛
     */
    void doFilter(RequestContext context) throws Exception;
}
