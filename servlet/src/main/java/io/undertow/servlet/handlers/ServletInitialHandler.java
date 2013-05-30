/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.servlet.handlers;

import java.util.concurrent.Executor;

import javax.servlet.DispatcherType;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import io.undertow.UndertowLogger;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.servlet.api.ServletDispatcher;
import io.undertow.servlet.api.ThreadSetupAction;
import io.undertow.servlet.core.ApplicationListeners;
import io.undertow.servlet.core.CompositeThreadSetupAction;
import io.undertow.servlet.core.ServletBlockingHttpExchange;
import io.undertow.servlet.spec.HttpServletRequestImpl;
import io.undertow.servlet.spec.HttpServletResponseImpl;
import io.undertow.servlet.spec.RequestDispatcherImpl;
import io.undertow.servlet.spec.ServletContextImpl;
import org.xnio.IoUtils;

/**
 * This must be the initial handler in the blocking servlet chain. This sets up the request and response objects,
 * and attaches them the to exchange.
 *
 * @author Stuart Douglas
 */
public class ServletInitialHandler implements HttpHandler, ServletDispatcher {

    private final HttpHandler next;
    //private final HttpHandler asyncPath;

    private final CompositeThreadSetupAction setupAction;

    private final ServletContextImpl servletContext;

    private final ApplicationListeners listeners;

    private final ServletPathMatches paths;

    public ServletInitialHandler(final ServletPathMatches paths, final HttpHandler next, final CompositeThreadSetupAction setupAction, final ServletContextImpl servletContext) {
        this.next = next;
        this.setupAction = setupAction;
        this.servletContext = servletContext;
        this.paths = paths;
        this.listeners = servletContext.getDeployment().getApplicationListeners();
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        final String path = exchange.getRelativePath();
        final ServletPathMatch info = paths.getServletHandlerByPath(path);
        final HttpServletResponseImpl response = new HttpServletResponseImpl(exchange, servletContext);
        final HttpServletRequestImpl request = new HttpServletRequestImpl(exchange, servletContext);
        final ServletRequestContext servletRequestContext = new ServletRequestContext(servletContext.getDeployment(), request, response);
        exchange.putAttachment(ServletRequestContext.ATTACHMENT_KEY, servletRequestContext);

        exchange.startBlocking(new ServletBlockingHttpExchange(exchange));
        servletRequestContext.setServletPathMatch(info);

        Executor executor = info.getExecutor();
        if (executor == null) {
            executor = servletContext.getDeployment().getExecutor();
        }

        if (exchange.isInIoThread() || executor != null) {
            //either the exchange has not been dispatched yet, or we need to use a special executor
            exchange.dispatch(executor, new HttpHandler() {
                @Override
                public void handleRequest(final HttpServerExchange exchange) throws Exception {
                    dispatchRequest(exchange, servletRequestContext, info, DispatcherType.REQUEST);
                }
            });
        } else {
            dispatchRequest(exchange, servletRequestContext, info, DispatcherType.REQUEST);
        }
    }

    public void dispatchToPath(final HttpServerExchange exchange, final ServletPathMatch pathInfo, final DispatcherType dispatcherType) throws Exception {
        final ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
        servletRequestContext.setServletPathMatch(pathInfo);
        dispatchRequest(exchange, servletRequestContext, pathInfo, dispatcherType);
    }

    @Override
    public void dispatchToServlet(final HttpServerExchange exchange, final ServletChain servletchain, final DispatcherType dispatcherType) throws Exception {
        final ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
        dispatchRequest(exchange, servletRequestContext, servletchain, dispatcherType);
    }

    private void dispatchRequest(final HttpServerExchange exchange, final ServletRequestContext servletRequestContext, final ServletChain servletChain, final DispatcherType dispatcherType) throws Exception {
        servletRequestContext.setDispatcherType(dispatcherType);
        servletRequestContext.setCurrentServlet(servletChain);
        if (dispatcherType == DispatcherType.REQUEST || dispatcherType == DispatcherType.ASYNC) {
            handleFirstRequest(exchange, servletChain, servletRequestContext, servletRequestContext.getServletRequest(), servletRequestContext.getServletResponse());
        } else {
            next.handleRequest(exchange);
        }
    }

    public void handleFirstRequest(final HttpServerExchange exchange, final ServletChain servletChain, final ServletRequestContext servletRequestContext, final ServletRequest request, final ServletResponse response) throws Exception {

        ThreadSetupAction.Handle handle = setupAction.setup(exchange);
        try {
            ServletRequestContext.setCurrentRequestContext(servletRequestContext);
            try {
                listeners.requestInitialized(request);
                next.handleRequest(exchange);
                //
            } catch (Throwable t) {
                if (request.isAsyncStarted() || request.getDispatcherType() == DispatcherType.ASYNC) {
                    exchange.unDispatch();
                    servletRequestContext.getOriginalRequest().getAsyncContextInternal().handleError(t);
                } else {
                    if (!exchange.isResponseStarted()) {
                        response.reset();                       //reset the response
                        exchange.setResponseCode(500);
                        exchange.getResponseHeaders().clear();
                        String location = servletContext.getDeployment().getErrorPages().getErrorLocation(t);
                        if (location != null) {
                            RequestDispatcherImpl dispatcher = new RequestDispatcherImpl(location, servletContext);
                            try {
                                dispatcher.error(request, response, servletChain.getManagedServlet().getServletInfo().getName(), t);
                            } catch (Exception e) {
                                UndertowLogger.REQUEST_LOGGER.errorf(e, "Exception while generating error page %s", location);
                            }
                        } else {
                            UndertowLogger.REQUEST_LOGGER.errorf(t, "Servlet request failed %s", exchange);
                        }
                    }
                }

            }
            servletContext.getDeployment().getApplicationListeners().requestDestroyed(request);
            if (!exchange.isDispatched()) {
                servletRequestContext.getOriginalResponse().responseDone();
                //this request is done, so we close any parser that may have been used
                final FormDataParser parser = exchange.getAttachment(FormDataParser.ATTACHMENT_KEY);
                IoUtils.safeClose(parser);
            }
        } finally {
            try {
                handle.tearDown();
            } finally {
                ServletRequestContext.clearCurrentServletAttachments();
            }
        }
    }

    public HttpHandler getNext() {
        return next;
    }

}
