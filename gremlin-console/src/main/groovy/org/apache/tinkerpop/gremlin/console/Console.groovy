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
package org.apache.tinkerpop.gremlin.console

import org.apache.commons.cli.Option
import org.apache.tinkerpop.gremlin.console.commands.GremlinSetCommand
import org.apache.tinkerpop.gremlin.console.commands.InstallCommand
import org.apache.tinkerpop.gremlin.console.commands.PluginCommand
import org.apache.tinkerpop.gremlin.console.commands.RemoteCommand
import org.apache.tinkerpop.gremlin.console.commands.SubmitCommand
import org.apache.tinkerpop.gremlin.console.commands.UninstallCommand
import org.apache.tinkerpop.gremlin.console.plugin.PluggedIn
import org.apache.tinkerpop.gremlin.groovy.loaders.GremlinLoader
import org.apache.tinkerpop.gremlin.groovy.plugin.GremlinPlugin
import jline.console.history.FileHistory
import org.apache.tinkerpop.gremlin.util.Gremlin
import org.apache.tinkerpop.gremlin.util.iterator.ArrayIterator
import org.codehaus.groovy.tools.shell.AnsiDetector
import org.codehaus.groovy.tools.shell.ExitNotification
import org.codehaus.groovy.tools.shell.Groovysh
import org.codehaus.groovy.tools.shell.IO
import org.codehaus.groovy.tools.shell.InteractiveShellRunner
import org.codehaus.groovy.tools.shell.commands.SetCommand
import org.codehaus.groovy.tools.shell.util.HelpFormatter
import org.codehaus.groovy.tools.shell.util.Preferences
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole

import java.util.prefs.PreferenceChangeEvent
import java.util.prefs.PreferenceChangeListener

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
class Console {
    static {
        // this is necessary so that terminal doesn't lose focus to AWT
        System.setProperty("java.awt.headless", "true")
        // must be called before IO(), since it modifies System.in
        // Install the system adapters, replaces System.out and System.err
        // Must be called before using IO(), because IO stores refs to System.out and System.err
        AnsiConsole.systemInstall()
        // Register jline ansi detector
        Ansi.setDetector(new AnsiDetector())
        Ansi.enabled = true
    }

    public static final String PREFERENCE_ITERATION_MAX = "max-iteration"
    private static final int DEFAULT_ITERATION_MAX = 100
    private static int maxIteration = DEFAULT_ITERATION_MAX

    private static final String STANDARD_INPUT_PROMPT = "gremlin> "
    private static final String STANDARD_RESULT_PROMPT = "==>"
    private static final String IMPORT_SPACE = "import "
    private static final String IMPORT_STATIC_SPACE = "import static "
    private static final String NULL = "null"
    private static final String ELLIPSIS = "..."

    private Iterator tempIterator = Collections.emptyIterator()

    private final IO io
    private final Groovysh groovy
    private final boolean interactive

    /**
     * @deprecated As of release 3.2.1.
     */
    @Deprecated
    public Console(final String initScriptFile) {
        this(new IO(System.in, System.out, System.err), initScriptFile.size() != null ? [initScriptFile] : null, true)
    }

    public Console(final IO io, final List<String> scriptAndArgs, final boolean interactive) {
        this.io = io
        this.interactive = interactive

        if (!io.quiet) {
            io.out.println()
            io.out.println("         \\,,,/")
            io.out.println("         (o o)")
            io.out.println("-----oOOo-(3)-oOOo-----")
        }

        maxIteration = Integer.parseInt(Preferences.get(PREFERENCE_ITERATION_MAX, Integer.toString(DEFAULT_ITERATION_MAX)))
        Preferences.addChangeListener(new PreferenceChangeListener() {
            @Override
            void preferenceChange(PreferenceChangeEvent evt) {
                if (evt.key == PREFERENCE_ITERATION_MAX)
                    maxIteration = Integer.parseInt(evt.newValue)
            }
        })

        final Mediator mediator = new Mediator(this)

        // make sure that remotes are closed if console takes a ctrl-c
        addShutdownHook { mediator.close() }

        groovy = new GremlinGroovysh(mediator)

        def commandsToRemove = groovy.getRegistry().commands().findAll{it instanceof SetCommand}
        commandsToRemove.each {groovy.getRegistry().remove(it)}
        groovy.register(new GremlinSetCommand(groovy))
        groovy.register(new UninstallCommand(groovy, mediator))
        groovy.register(new InstallCommand(groovy, mediator))
        groovy.register(new PluginCommand(groovy, mediator))
        groovy.register(new RemoteCommand(groovy, mediator))
        groovy.register(new SubmitCommand(groovy, mediator))

        // hide output temporarily while imports execute
        showShellEvaluationOutput(false)

        // add the default imports
        new ConsoleImportCustomizerProvider().getCombinedImports().stream()
                .collect { IMPORT_SPACE + it }.each { groovy.execute(it) }
        new ConsoleImportCustomizerProvider().getCombinedStaticImports().stream()
                .collect { IMPORT_STATIC_SPACE + it }.each { groovy.execute(it) }

        final InteractiveShellRunner runner = new InteractiveShellRunner(groovy, handlePrompt)
        runner.setErrorHandler(handleError)
        try {
            final FileHistory history = new FileHistory(new File(ConsoleFs.HISTORY_FILE))
            groovy.setHistory(history)
            runner.setHistory(history)
        } catch (IOException ignored) {
            io.err.println("Unable to create history file: " + ConsoleFs.HISTORY_FILE)
        }

        GremlinLoader.load()

        // check for available plugins.  if they are in the "active" plugins strategies then "activate" them
        def activePlugins = Mediator.readPluginState()
        ServiceLoader.load(GremlinPlugin.class, groovy.getInterp().getClassLoader()).each { plugin ->
            if (!mediator.availablePlugins.containsKey(plugin.class.name)) {
                def pluggedIn = new PluggedIn(plugin, groovy, io, false)
                mediator.availablePlugins.put(plugin.class.name, pluggedIn)

                if (activePlugins.contains(plugin.class.name)) {
                    pluggedIn.activate()

                    if (!io.quiet)
                        io.out.println("plugin activated: " + plugin.getName())
                }
            }
        }

        // remove any "uninstalled" plugins from plugin state as it means they were installed, activated, but not
        // deactivated, and are thus hanging about
        mediator.writePluginState()

        try {
            // if the init script contains :x command it will throw an ExitNotification so init script execution
            // needs to appear in the try/catch
            if (scriptAndArgs != null && scriptAndArgs.size() > 0) executeInShell(scriptAndArgs)

            // start iterating results to show as output
            showShellEvaluationOutput(true)

            runner.run()
        } catch (ExitNotification ignored) {
            // occurs on exit
        } catch (Throwable t) {
            t.printStackTrace()
        } finally {
            // shutdown hook defined above will kill any open remotes
            System.exit(0)
        }
    }

    def showShellEvaluationOutput(final boolean show) {
        if (show)
            groovy.setResultHook(handleResultIterate)
        else
            groovy.setResultHook(handleResultShowNothing)
    }

    private def handlePrompt = { interactive ? STANDARD_INPUT_PROMPT : "" }

    private def handleResultShowNothing = { args -> null }

    private def handleResultIterate = { result ->
        try {
            // necessary to save persist history to file
            groovy.getHistory().flush()
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e)
        }

        while (true) {
            if (this.tempIterator.hasNext()) {
                int counter = 0;
                while (this.tempIterator.hasNext() && (maxIteration == -1 || counter < maxIteration)) {
                    final Object object = this.tempIterator.next()
                    io.out.println(buildResultPrompt() + ((null == object) ? NULL : object.toString()))
                    counter++;
                }
                if(this.tempIterator.hasNext())
                    io.out.println(ELLIPSIS);
                this.tempIterator = Collections.emptyIterator();
                return null
            } else {
                try {
                    // if the result is an empty iterator then the tempIterator needs to be set to one, as a
                    // future assignment to the strategies that produced the iterator will maintain that reference
                    // and try to iterate it above.  in other words, this:
                    //
                    // x =[]
                    // x << "test"
                    //
                    // would throw a ConcurrentModificationException because the assignment of x to the tempIterator
                    // on the first line would maintain a reference on the next result iteration call and would
                    // drop into the other part of this if statement and throw.
                    if (result instanceof Iterator) {
                        this.tempIterator = (Iterator) result
                        if (!this.tempIterator.hasNext()) {
                            this.tempIterator = Collections.emptyIterator();
                            return null
                        }
                    } else if (result instanceof Iterable) {
                        this.tempIterator = ((Iterable) result).iterator()
                        if (!this.tempIterator.hasNext()) {
                            this.tempIterator = Collections.emptyIterator();
                            return null
                        }
                    } else if (result instanceof Object[]) {
                        this.tempIterator = new ArrayIterator((Object[]) result)
                        if (!this.tempIterator.hasNext()) {
                            this.tempIterator = Collections.emptyIterator();
                            return null
                        }
                    } else if (result instanceof Map) {
                        this.tempIterator = ((Map) result).entrySet().iterator()
                        if (!this.tempIterator.hasNext()) {
                            this.tempIterator = Collections.emptyIterator();
                            return null
                        }
                    } else {
                        io.out.println(buildResultPrompt() + ((null == result) ? NULL : result.toString()))
                        return null
                    }
                } catch (final Exception e) {
                    this.tempIterator = Collections.emptyIterator()
                    throw e
                }
            }
        }
    }

    private def handleError = { err ->
        this.tempIterator = Collections.emptyIterator();
        if (err instanceof Throwable) {
            try {
                final Throwable e = (Throwable) err
                String message = e.getMessage()
                if (null != message) {
                    message = message.replace("startup failed:", "")
                    io.err.println(message.trim())
                } else {
                    io.err.println(e)
                }

                if (interactive) {
                    io.err.print("Display stack trace? [yN] ")
                    io.err.flush()
                    String line = new BufferedReader(io.in).readLine()
                    if (null == line)
                        line = ""
                    io.err.print(line.trim())
                    io.err.println()
                    if (line.trim().equals("y") || line.trim().equals("Y")) {
                        e.printStackTrace(io.err)
                    }
                } else {
                    e.printStackTrace(io.err)
                    System.exit(1)
                }
            } catch (Exception ignored) {
                io.err.println("An undefined error has occurred: " + err)
                if (!interactive) System.exit(1)
            }
        } else {
            io.err.println("An undefined error has occurred: " + err.toString())
            if (!interactive) System.exit(1)
        }

        return null
    }

    private static String buildResultPrompt() {
        final String groovyshellProperty = System.getProperty("gremlin.prompt")
        if (groovyshellProperty != null)
            return groovyshellProperty

        final String groovyshellEnv = System.getenv("GREMLIN_PROMPT")
        if (groovyshellEnv != null)
            return groovyshellEnv

        return STANDARD_RESULT_PROMPT
    }

    private void executeInShell(final List<String> scriptAndArgs) {
        final String scriptFile = scriptAndArgs[0]
        try {
            // check if this script comes with arguments. if so then set them up in an "args" bundle
            if (scriptAndArgs.size() > 1) {
                List<String> args = scriptAndArgs.subList(1, scriptAndArgs.size())
                groovy.execute("args = [\"" + args.join('\",\"') + "\"]")
            } else {
                groovy.execute("args = []")
            }

            final File file = new File(scriptFile)
            int lineNumber = 0
            def lines = file.readLines()
            for (String line : lines) {
                try {
                    lineNumber++
                    groovy.execute(line)
                } catch (Exception ex) {
                    io.err.println("Error in $scriptFile at [$lineNumber: $line] - ${ex.message}")
                    if (interactive)
                        break
                    else {
                        ex.printStackTrace(io.err)
                        System.exit(1)
                    }

                }
            }

            if (!interactive) System.exit(0)
        } catch (FileNotFoundException ignored) {
            io.err.println("Gremlin file not found at [$scriptFile].")
            if (!interactive) System.exit(1)
        } catch (Exception ex) {
            io.err.println("Failure processing Gremlin script [$scriptFile] - ${ex.message}")
            if (!interactive) System.exit(1)
        }
    }

    public static void main(final String[] args) {
        // need to do some up front processing to try to support "bin/gremlin.sh init.groovy" until this deprecated
        // feature can be removed. ultimately this should be removed when a breaking change can go in
        IO io = new IO(System.in, System.out, System.err)
        if (args.length == 1 && !args[0].startsWith("-"))
            new Console(io, [args[0]], true)

        final CliBuilder cli = new CliBuilder(usage: 'gremlin.sh [options] [...]', formatter: new HelpFormatter(), stopAtNonOption: false)

        // note that the inclusion of -l is really a setting handled by gremlin.sh and not by Console class itself.
        // it is mainly listed here for informational purposes when the user starts things up with -h
        cli.with {
            h(longOpt: 'help', "Display this help message")
            v(longOpt: 'version', "Display the version")
            l("Set the logging level of components that use standard logging output independent of the Console")
            V(longOpt: 'verbose', "Enable verbose Console output")
            Q(longOpt: 'quiet', "Suppress superfluous Console output")
            D(longOpt: 'debug', "Enabled debug Console output")
            i(longOpt: 'interactive', argName: "SCRIPT ARG1 ARG2 ...", args: Option.UNLIMITED_VALUES, valueSeparator: ' ' as char, "Execute the specified script and leave the console open on completion")
            e(longOpt: 'execute', argName: "SCRIPT ARG1 ARG2 ...", args: Option.UNLIMITED_VALUES, valueSeparator: ' ' as char, "Execute the specified script (SCRIPT ARG1 ARG2 ...) and close the console on completion")
        }
        OptionAccessor options = cli.parse(args)

        if (options == null) {
            // CliBuilder prints error, but does not exit
            System.exit(22) // Invalid Args
        }

        if (options.h) {
            cli.usage()
            System.exit(0)
        }

        if (options.v) {
            println("gremlin " + Gremlin.version())
            System.exit(0)
        }

        if (options.V) io.verbosity = IO.Verbosity.VERBOSE
        if (options.D) io.verbosity = IO.Verbosity.DEBUG
        if (options.Q) io.verbosity = IO.Verbosity.QUIET

        // override verbosity if not explicitly set and -e is used
        if (options.e && (!options.V && !options.D && !options.Q))
            io.verbosity = IO.Verbosity.QUIET

        if (options.i && options.e) {
            println("-i and -e options are mutually exclusive - provide one or the other")
            System.exit(0)
        }

        List<String> scriptAndArgs = options.e ?
                (options.es != null && options.es ? options.es : null) :
                (options.is != null && options.is ? options.is : null)
        new Console(io, scriptAndArgs, !options.e)
    }
}
