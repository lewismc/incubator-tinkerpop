/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.server.op.session;

import org.apache.tinkerpop.gremlin.driver.Tokens;
import org.apache.tinkerpop.gremlin.driver.message.RequestMessage;
import org.apache.tinkerpop.gremlin.driver.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.driver.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.handler.StateKey;
import org.apache.tinkerpop.gremlin.server.op.AbstractEvalOpProcessor;
import org.apache.tinkerpop.gremlin.server.op.OpProcessorException;
import org.apache.tinkerpop.gremlin.server.util.MetricManager;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.Bindings;
import javax.script.SimpleBindings;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Simple {@link org.apache.tinkerpop.gremlin.server.OpProcessor} implementation that handles
 * {@code ScriptEngine} script evaluation in the context of a session. Note that this processor will
 * also take a "close" op to kill the session and rollback any incomplete transactions.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class SessionOpProcessor extends AbstractEvalOpProcessor {
    private static final Logger logger = LoggerFactory.getLogger(SessionOpProcessor.class);
    public static final String OP_PROCESSOR_NAME = "session";

    /**
     * Script engines are evaluated in a per session context where imports/scripts are isolated per session.
     */
    protected static ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    static {
        MetricManager.INSTANCE.getGuage(sessions::size, name(GremlinServer.class, "sessions"));
    }

    /**
     * Configuration setting for how long a session will be available before it timesout.
     */
    public static final String CONFIG_SESSION_TIMEOUT = "sessionTimeout";

    /**
     * Default timeout for a session is eight hours.
     */
    public static final long DEFAULT_SESSION_TIMEOUT = 28800000l;

    static final Settings.ProcessorSettings DEFAULT_SETTINGS = new Settings.ProcessorSettings();

    static {
        DEFAULT_SETTINGS.className = SessionOpProcessor.class.getCanonicalName();
        DEFAULT_SETTINGS.config = new HashMap<String, Object>() {{
            put(CONFIG_SESSION_TIMEOUT, DEFAULT_SESSION_TIMEOUT);
        }};
    }

    public SessionOpProcessor() {
        super(false);
    }

    @Override
    public String getName() {
        return OP_PROCESSOR_NAME;
    }

    /**
     * Session based requests accept a "close" operator in addition to "eval".  A close will trigger the session to be
     * killed and any uncommitted transaction to be rolled-back.
     */
    @Override
    public Optional<ThrowingConsumer<Context>> selectOther(final RequestMessage requestMessage)  throws OpProcessorException {
        if (requestMessage.getOp().equals(Tokens.OPS_CLOSE)) {
            // this must be an in-session request
            if (!requestMessage.optionalArgs(Tokens.ARGS_SESSION).isPresent()) {
                final String msg = String.format("A message with an [%s] op code requires a [%s] argument", Tokens.OPS_CLOSE, Tokens.ARGS_SESSION);
                throw new OpProcessorException(msg, ResponseMessage.build(requestMessage).code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg).create());
            }

            return Optional.of(ctx -> {
                // validate the session is present and then remove it if it is.
                final Session sessionToClose = sessions.get(requestMessage.getArgs().get(Tokens.ARGS_SESSION).toString());
                if (null == sessionToClose) {
                    final String msg = String.format("There was no session named %s to close", requestMessage.getArgs().get(Tokens.ARGS_SESSION).toString());
                    throw new OpProcessorException(msg, ResponseMessage.build(requestMessage).code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg).create());
                }

                sessionToClose.manualKill();
            });
        } else {
            return Optional.empty();
        }
    }

    @Override
    public ThrowingConsumer<Context> getEvalOp() {
        return this::evalOp;
    }

    @Override
    protected Optional<ThrowingConsumer<Context>> validateEvalMessage(final RequestMessage message) throws OpProcessorException {
        super.validateEvalMessage(message);

        if (!message.optionalArgs(Tokens.ARGS_SESSION).isPresent()) {
            final String msg = String.format("A message with an [%s] op code requires a [%s] argument", Tokens.OPS_EVAL, Tokens.ARGS_SESSION);
            throw new OpProcessorException(msg, ResponseMessage.build(message).code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg).create());
        }

        return Optional.empty();
    }

    @Override
    public void close() throws Exception {
       sessions.values().forEach(Session::manualKill);
    }

    protected void evalOp(final Context context) throws OpProcessorException {
        final RequestMessage msg = context.getRequestMessage();
        final Session session = getSession(context, msg);

        // place the session on the channel context so that it can be used during serialization.  in this way
        // the serialization can occur on the same thread used to execute the gremlin within the session.  this
        // is important given the threadlocal nature of Graph implementation transactions.
        context.getChannelHandlerContext().channel().attr(StateKey.SESSION).set(session);

        evalOpInternal(context, session::getGremlinExecutor, getBindingMaker(session).apply(context));
    }

    /**
     * Examines the {@link RequestMessage} and extracts the session token. The session is then either found or a new
     * one is created.
     */
    protected static Session getSession(final Context context, final RequestMessage msg) {
        final String sessionId = (String) msg.getArgs().get(Tokens.ARGS_SESSION);

        logger.debug("In-session request {} for eval for session {} in thread {}",
                msg.getRequestId(), sessionId, Thread.currentThread().getName());

        final Session session = sessions.computeIfAbsent(sessionId, k -> new Session(k, context, sessions));
        session.touch();
        return session;
    }

    /**
     * A useful method for those extending this class, where the means for binding construction can be supplied
     * to this class.  This function is used in {@link #evalOp(Context)} to create the final argument to
     * {@link AbstractEvalOpProcessor#evalOpInternal(Context, Supplier, org.apache.tinkerpop.gremlin.server.op.AbstractEvalOpProcessor.BindingSupplier)}.
     * In this way an extending class can use the default {@link org.apache.tinkerpop.gremlin.server.op.AbstractEvalOpProcessor.BindingSupplier}
     * which carries a lot of re-usable functionality or provide a new one to override the existing approach.
     */
    protected Function<Context, BindingSupplier> getBindingMaker(final Session session) {
        return context -> () -> {
            final RequestMessage msg = context.getRequestMessage();
            final Bindings bindings = session.getBindings();

            // don't allow both rebindings and aliases parameters as they are the same thing. aliases were introduced
            // as of 3.1.0 as a replacement for rebindings. this check can be removed when rebindings are completely
            // removed from the protocol
            final boolean hasRebindings = msg.getArgs().containsKey(Tokens.ARGS_REBINDINGS);
            final boolean hasAliases = msg.getArgs().containsKey(Tokens.ARGS_ALIASES);
            if (hasRebindings && hasAliases) {
                final String error = "Prefer use of the 'aliases' parameter over 'rebindings' and do not use both";
                throw new OpProcessorException(error, ResponseMessage.build(msg)
                        .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(error).create());
            }

            final String rebindingOrAliasParameter = hasRebindings ? Tokens.ARGS_REBINDINGS : Tokens.ARGS_ALIASES;

            // alias any global bindings to a different variable
            if (msg.getArgs().containsKey(rebindingOrAliasParameter)) {
                final Map<String, String> aliases = (Map<String, String>) msg.getArgs().get(rebindingOrAliasParameter);
                for (Map.Entry<String,String> aliasKv : aliases.entrySet()) {
                    boolean found = false;

                    // first check if the alias refers to a Graph instance
                    final Map<String, Graph> graphs = context.getGraphManager().getGraphs();
                    if (graphs.containsKey(aliasKv.getValue())) {
                        bindings.put(aliasKv.getKey(), graphs.get(aliasKv.getValue()));
                        found = true;
                    }

                    // if the alias wasn't found as a Graph then perhaps it is a TraversalSource - it needs to be
                    // something
                    if (!found) {
                        final Map<String, TraversalSource> traversalSources = context.getGraphManager().getTraversalSources();
                        if (traversalSources.containsKey(aliasKv.getValue())) {
                            bindings.put(aliasKv.getKey(), traversalSources.get(aliasKv.getValue()));
                            found = true;
                        }
                    }

                    // this validation is important to calls to GraphManager.commit() and rollback() as they both
                    // expect that the aliases supplied are valid
                    if (!found) {
                        final String error = String.format("Could not alias [%s] to [%s] as [%s] not in the Graph or TraversalSource global bindings",
                                aliasKv.getKey(), aliasKv.getValue(), aliasKv.getValue());
                        throw new OpProcessorException(error, ResponseMessage.build(msg)
                                .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(error).create());
                    }
                }
            }

            // add any bindings to override any other supplied
            Optional.ofNullable((Map<String, Object>) msg.getArgs().get(Tokens.ARGS_BINDINGS)).ifPresent(bindings::putAll);
            return bindings;
        };
    }
}
