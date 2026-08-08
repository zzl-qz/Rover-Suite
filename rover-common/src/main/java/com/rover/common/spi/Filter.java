package com.rover.common.spi;

public interface Filter {

    void doFilter(RequestContext context, FilterChain chain);
}
