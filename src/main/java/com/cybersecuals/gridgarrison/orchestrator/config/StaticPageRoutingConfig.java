package com.cybersecuals.gridgarrison.orchestrator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class StaticPageRoutingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/panel").setViewName("forward:/panel.html");
        registry.addViewController("/visualizer").setViewName("forward:/visualizer.html");
        registry.addViewController("/ev-control-panel").setViewName("forward:/panel.html");
    }
}
