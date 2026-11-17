package com.cmms.config;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Folder holding the built React app (frontend/dist). Used on Windows, where there is no nginx. */
    @Value("${CMMS_WEB_DIR:./web}")
    private String webDir;

    /** Dev only: allow the Vite dev server. In production the UI and API share one origin. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("http://localhost:5173").allowedMethods("*");
    }

    /** "/" never reaches resource handlers, so forward it to the app explicitly. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * Serve the web UI from CMMS_WEB_DIR if it exists. Unknown paths such as /work-orders/5 return index.html
     * so React Router can handle them. /api paths are never served from here.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Path.of(webDir).toAbsolutePath().normalize();
        registry.addResourceHandler("/**")
                .addResourceLocations(root.toUri().toString().replaceAll("/?$", "/")) // file:///C:/.../web/ on Windows
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.startsWith("api/") || resourcePath.equals("api")) return null;
                        Resource r = resourcePath.isEmpty() ? null : super.getResource(resourcePath, location);
                        if (r != null && r.isReadable() && !r.getFile().isDirectory()) return r;
                        if (resourcePath.contains(".")) return null; // missing asset -> 404, not index.html
                        FileSystemResource index = new FileSystemResource(root.resolve("index.html"));
                        return index.exists() ? index : null;
                    }
                });
    }
}
