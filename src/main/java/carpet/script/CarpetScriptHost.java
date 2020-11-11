package carpet.script;

import carpet.CarpetServer;
import carpet.script.api.Auxiliary;
import carpet.script.bundled.Module;
import carpet.script.command.CommandArgument;
import carpet.script.exception.CarpetExpressionException;
import carpet.script.exception.ExpressionException;
import carpet.script.exception.InternalExpressionException;
import carpet.script.exception.InvalidCallbackException;
import carpet.script.value.FunctionValue;
import carpet.script.value.MapValue;
import carpet.script.value.NumericValue;
import carpet.script.value.StringValue;
import carpet.script.value.Value;
import carpet.CarpetSettings;
import carpet.utils.Messenger;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.Tag;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.Math.max;

public class CarpetScriptHost extends ScriptHost
{
    private final CarpetScriptServer scriptServer;
    ServerCommandSource responsibleSource;

    private Tag globalState;
    private int saveTimeout;
    public boolean persistenceRequired;

    public Map<Value, Value> appConfig;
    public Map<String, CommandArgument> appArgTypes;

    private CarpetScriptHost(CarpetScriptServer server, Module code, boolean perUser, ScriptHost parent, Map<Value, Value> config, Map<String, CommandArgument> argTypes)
    {
        super(code, perUser, parent);
        this.saveTimeout = 0;
        this.scriptServer = server;
        persistenceRequired = true;
        if (parent == null && code != null) // app, not a global host
        {
            persistenceRequired = false;
            globalState = loadState();
        }
        else if (parent != null)
        {
            persistenceRequired = ((CarpetScriptHost)parent).persistenceRequired;
        }
        appConfig = config;
        appArgTypes = argTypes;
    }

    public static CarpetScriptHost create(CarpetScriptServer scriptServer, Module module, boolean perPlayer, ServerCommandSource source)
    {
        CarpetScriptHost host = new CarpetScriptHost(scriptServer, module, perPlayer, null, Collections.emptyMap(), new HashMap<>());
        // parse code and convert to expression
        if (module != null)
        {
            try
            {
                String code = module.getCode();
                if (code == null)
                {
                    Messenger.m(source, "r Unable to load "+module.getName()+" app - code not found");
                    return null;
                }
                host.setChatErrorSnooper(source);
                CarpetExpression ex = new CarpetExpression(host.main, code, source, new BlockPos(0, 0, 0));
                ex.getExpr().asATextSource();
                ex.scriptRunCommand(host, new BlockPos(source.getPosition()));
            }
            catch (CarpetExpressionException e)
            {
                host.handleErrorWithStack("Error while evaluating expression", e);
                host.resetErrorSnooper();
                return null;
            }
            catch (ArithmeticException ae)
            {
                host.handleErrorWithStack("Math doesn't compute", ae);
                return null;
            }
        }
        return host;
    }

    @Override
    protected ScriptHost duplicate()
    {
        return new CarpetScriptHost(scriptServer, main, false, this, appConfig, appArgTypes);
    }

    @Override
    public void addUserDefinedFunction(Context ctx, Module module, String funName, FunctionValue function)
    {
        super.addUserDefinedFunction(ctx, module, funName, function);
        if (ctx.host.main != module) return; // not dealing with automatic imports / exports /configs / apps from imports
        if (funName.startsWith("__")) // potential fishy activity
        {
            if (funName.startsWith("__on_")) // here we can make a determination if we want to only accept events from main module.
            {
                // this is nasty, we have the host and function, yet we add it via names, but hey - works for now
                String event = funName.replaceFirst("__on_", "");
                if (CarpetEventServer.Event.byName.containsKey(event))
                    scriptServer.events.addBuiltInEvent(event, this, function, null);
            }
            else if (funName.equals("__config"))
            {
                // needs to be added as we read the code, cause other events may be affected.
                if (!readConfig())
                    throw new InternalExpressionException("Invalid app config (via '__config()' function)");
            }
        }
    }

    private boolean readConfig()
    {
        try
        {
            Value ret = callUDF(BlockPos.ORIGIN, scriptServer.server.getCommandSource(), getFunction("__config"), Collections.emptyList());
            if (!(ret instanceof MapValue)) return false;
            Map<Value, Value> config = ((MapValue) ret).getMap();
            setPerPlayer(config.getOrDefault(new StringValue("scope"), new StringValue("player")).getString().equalsIgnoreCase("player"));
            persistenceRequired = config.getOrDefault(new StringValue("stay_loaded"), Value.FALSE).getBoolean();
            // read custom arguments
            Value arguments = config.get(StringValue.of("arguments"));
            if (arguments != null)
            {
                if (!(arguments instanceof MapValue))
                    throw new InternalExpressionException("'arguments' element in config should be a map");
                for (Map.Entry<Value, Value> typeData : ((MapValue)arguments).getMap().entrySet())
                {
                    String argument = typeData.getKey().getString();
                    Value spec = typeData.getValue();
                    if (!(spec instanceof MapValue)) throw new InternalExpressionException("Spec for '"+argument+"' should be a map");
                    Map<String, Value> specData = ((MapValue) spec).getMap().entrySet().stream().collect(Collectors.toMap(e -> e.getKey().getString(), Map.Entry::getValue));
                    appArgTypes.put(argument, CommandArgument.buildFromConfig(argument, specData));
                }
            }
            appConfig = config;
        }
        catch (NullPointerException | InvalidCallbackException ignored)
        {
            return false;
        }
        return true;
    }

    @Override
    protected Module getModuleOrLibraryByName(String name)
    {
        Module module = scriptServer.getModule(name, true);
        if (module == null || module.getCode() == null)
            throw new InternalExpressionException("Unable to locate package: "+name);
        return module;
    }

    @Override
    protected void runModuleCode(Context c, Module module)
    {
        CarpetContext cc = (CarpetContext)c;
        CarpetExpression ex = new CarpetExpression(module, module.getCode(), cc.s, cc.origin);
        ex.getExpr().asATextSource();
        ex.scriptRunCommand(this, cc.origin);
    }

    @Override
    public void delFunction(Module module, String funName)
    {
        super.delFunction(module, funName);
        // mcarpet
        if (funName.startsWith("__on_"))
        {
            // this is nasty, we have the host and function, yet we add it via names, but hey - works for now
            String event = funName.replaceFirst("__on_","");
            scriptServer.events.removeBuiltInEvent(event, this, funName);
        }
    }

    public CarpetScriptHost retrieveForExecution(ServerCommandSource source)
    {
        CarpetScriptHost host = this;
        if (perUser)
        {
            try
            {
                ServerPlayerEntity player = source.getPlayer();
                host = (CarpetScriptHost) retrieveForExecution(player.getName().getString());
            }
            catch (CommandSyntaxException e)
            {
                host = (CarpetScriptHost)  retrieveForExecution((String) null);
            }
        }
        if (host.errorSnooper == null) host.setChatErrorSnooper(source);
        return host;
    }

    public CarpetScriptHost retrieveForExecution(ServerPlayerEntity target)
    {
        // allowing only run
        if (perUser == (target == null)) return null;

        if (!perUser)
        {
            if (errorSnooper == null) setChatErrorSnooper(this.scriptServer.server.getCommandSource());
            return this;
        }
        // user based
        CarpetScriptHost userHost = (CarpetScriptHost)retrieveForExecution(target.getName().getString());
        if (userHost.errorSnooper == null) userHost.setChatErrorSnooper(target.getCommandSource());
        return userHost;
    }

    public Value handleCommandLegacy(ServerCommandSource source, String call, List<Integer> coords, String arg)
    {
        try
        {
            return callLegacy(source, call, coords, arg);
        }
        catch (CarpetExpressionException exc)
        {
            handleErrorWithStack("Error while running custom command", exc);
            return Value.NULL;
        }
        catch (ArithmeticException ae)
        {
            handleErrorWithStack("Math doesn't compute", ae);
            return Value.NULL;
        }
    }

    public Value handleCommand(ServerCommandSource source, FunctionValue function, List<Value> args)
    {
        try
        {
            return call(source, function, args);
        }
        catch (CarpetExpressionException exc)
        {
            handleErrorWithStack("Error while running custom command", exc);
            return Value.NULL;
        }
        catch (ArithmeticException ae)
        {
            handleErrorWithStack("Math doesn't compute", ae);
            return Value.NULL;
        }
    }

    public Value callLegacy(ServerCommandSource source, String call, List<Integer> coords, String arg)
    {
        if (CarpetServer.scriptServer.stopAll)
            throw new CarpetExpressionException("SCARPET PAUSED", null);
        FunctionValue function = getFunction(call);
        if (function == null)
            throw new CarpetExpressionException("UNDEFINED", null);
        List<LazyValue> argv = new ArrayList<>();
        if (coords != null)
            for (Integer i: coords)
                argv.add( (c, t) -> new NumericValue(i));
        String sign = "";
        for (Tokenizer.Token tok : Tokenizer.simplepass(arg))
        {
            switch (tok.type)
            {
                case VARIABLE:
                    LazyValue var = getGlobalVariable(tok.surface);
                    if (var != null)
                    {
                        argv.add(var);
                        break;
                    }
                case STRINGPARAM:
                    argv.add((c, t) -> new StringValue(tok.surface));
                    sign = "";
                    break;

                case LITERAL:
                    try
                    {
                        String finalSign = sign;
                        argv.add((c, t) ->new NumericValue(finalSign+tok.surface));
                        sign = "";
                    }
                    catch (NumberFormatException exception)
                    {
                        throw new CarpetExpressionException("Fail: "+sign+tok.surface+" seems like a number but it is" +
                                " not a number. Use quotes to ensure its a string", null);
                    }
                    break;
                case HEX_LITERAL:
                    try
                    {
                        String finalSign = sign;
                        argv.add((c, t) -> new NumericValue(new BigInteger(finalSign+tok.surface.substring(2), 16).doubleValue()));
                        sign = "";
                    }
                    catch (NumberFormatException exception)
                    {
                        throw new CarpetExpressionException("Fail: "+sign+tok.surface+" seems like a number but it is" +
                                " not a number. Use quotes to ensure its a string", null);
                    }
                    break;
                case OPERATOR:
                case UNARY_OPERATOR:
                    if ((tok.surface.equals("-") || tok.surface.equals("-u")) && sign.isEmpty())
                    {
                        sign = "-";
                    }
                    else
                    {
                        throw new CarpetExpressionException("Fail: operators, like " + tok.surface + " are not " +
                                "allowed in invoke", null);
                    }
                    break;
                case FUNCTION:
                    throw new CarpetExpressionException("Fail: passing functions like "+tok.surface+"() to invoke is " +
                            "not allowed", null);
                case OPEN_PAREN:
                case COMMA:
                case CLOSE_PAREN:
                case MARKER:
                    throw new CarpetExpressionException("Fail: "+tok.surface+" is not allowed in invoke", null);
            }
        }
        List<String> args = function.getArguments();
        if (argv.size() != args.size())
        {
            String error = "Fail: stored function "+call+" takes "+args.size()+" arguments, not "+argv.size()+ ":\n";
            for (int i = 0; i < max(argv.size(), args.size()); i++)
            {
                error += (i<args.size()?args.get(i):"??")+" => "+(i<argv.size()?argv.get(i).evalValue(null).getString():"??")+"\n";
            }
            throw new CarpetExpressionException(error, null);
        }
        try
        {
            // TODO: this is just for now - invoke would be able to invoke other hosts scripts
            Context context = new CarpetContext(this, source, BlockPos.ORIGIN);
            return function.getExpression().evalValue(
                    () -> function.lazyEval(context, Context.VOID, function.getExpression(), function.getToken(), argv),
                    context,
                    Context.VOID
            );
        }
        catch (ExpressionException e)
        {
            throw new CarpetExpressionException(e.getMessage(), e.stack);
        }
    }

    public Value call(ServerCommandSource source, FunctionValue function, List<Value> suppliedArgs)
    {
        if (CarpetServer.scriptServer.stopAll)
            throw new CarpetExpressionException("SCARPET PAUSED", null);

        List<LazyValue> argv = FunctionValue.lazify(suppliedArgs);

        List<String> args = function.getArguments();
        if (argv.size() != args.size())
        {
            String error = "Fail: stored function "+function.getPrettyString()+" takes "+args.size()+" arguments, not "+argv.size()+ ":\n";
            for (int i = 0; i < max(argv.size(), args.size()); i++)
            {
                error += (i<args.size()?args.get(i):"??")+" => "+(i<argv.size()?argv.get(i).evalValue(null).getString():"??")+"\n";
            }
            throw new CarpetExpressionException(error, null);
        }
        try
        {
            // TODO: this is just for now - invoke would be able to invoke other hosts scripts
            Context context = new CarpetContext(this, source, BlockPos.ORIGIN);
            return function.getExpression().evalValue(
                    () -> function.lazyEval(context, Context.VOID, function.getExpression(), function.getToken(), argv),
                    context,
                    Context.VOID
            );
        }
        catch (ExpressionException e)
        {
            throw new CarpetExpressionException(e.getMessage(), e.stack);
        }
    }

    public Value callUDF(BlockPos pos, ServerCommandSource source, FunctionValue fun, List<Value> argv) throws InvalidCallbackException
    {
        if (CarpetServer.scriptServer.stopAll)
            return Value.NULL;
        try { // cause we can't throw checked exceptions in lambda. Left if be until need to handle these more gracefully
            fun.assertArgsOk(argv, (b) -> {
                throw new InternalExpressionException("");
            });
        }
        catch (InternalExpressionException ignored)
        {
            throw new InvalidCallbackException();
        }
        try
        {
            // TODO: this is just for now - invoke would be able to invoke other hosts scripts
            Context context = new CarpetContext(this, source, pos);
            return fun.getExpression().evalValue(
                    () -> fun.lazyEval(context, Context.VOID, fun.getExpression(), fun.getToken(), FunctionValue.lazify(argv)),
                    context,
                    Context.VOID);
        }
        catch (ExpressionException e)
        {
            handleExpressionException("Callback failed", e);
        }
        return Value.NULL;
    }


    @Override
    public void onClose()
    {
        super.onClose();
        FunctionValue closing = getFunction("__on_close");
        if (closing != null)
        {
            ServerPlayerEntity player = (user==null)?null:scriptServer.server.getPlayerManager().getPlayer(user);
            ServerCommandSource source = (player != null)?player.getCommandSource():scriptServer.server.getCommandSource();
            try
            {
                callUDF(BlockPos.ORIGIN, source, closing, Collections.emptyList());
            }
            catch (InvalidCallbackException ignored)
            {
            }
        }
        if (user == null)
        {

            String markerName = Auxiliary.MARKER_STRING + "_" + ((getName() == null) ? "" : getName());
            for (ServerWorld world : scriptServer.server.getWorlds())
            {
                for (Entity e : world.getEntitiesByType(EntityType.ARMOR_STAND, (as) -> as.getScoreboardTags().contains(markerName)))
                {
                    e.remove();
                }
            }
            if (this.saveTimeout > 0)
                dumpState();
        }
    }

    private void dumpState()
    {
        Module.saveData(main, null, globalState, false);
    }

    private Tag loadState()
    {
        return Module.getData(main, null, false);
    }

    public Tag readFileTag(String file, boolean isShared)
    {
        if (getName() == null && !isShared) return null;
        if (file != null)
            return Module.getData(main, file, isShared);
        if (parent == null)
            return globalState;
        return ((CarpetScriptHost)parent).globalState;
    }

    public boolean writeTagFile(Tag tag, String file, boolean isShared)
    {
        if (getName() == null && !isShared) return false; // if belongs to an app, cannot be default host.

        if (file!= null)
        {
            return Module.saveData(main, file, tag, isShared);
        }

        CarpetScriptHost responsibleHost = (parent != null)?(CarpetScriptHost) parent:this;
        responsibleHost.globalState = tag;
        if (responsibleHost.saveTimeout == 0)
        {
            responsibleHost.dumpState();
            responsibleHost.saveTimeout = 200;
        }
        return true;
    }

    public boolean removeResourceFile(String resource, boolean isShared, String type)
    {
        if (getName() == null && !isShared) return false; //
        return Module.dropExistingFile(main, resource, type.equals("nbt")?"nbt":"txt", isShared);
    }

    public boolean appendLogFile(String resource, boolean isShared, String type, List<String> data)
    {
        if (getName() == null && !isShared) return false; // if belongs to an app, cannot be default host.
        return Module.appendToTextFile(main, resource, type, isShared, data);
    }

    public List<String> readTextResource(String resource, boolean isShared)
    {
        if (getName() == null && !isShared) return null; //
        return Module.listFile(main, resource, "txt", isShared);
    }


    public void tick()
    {
        if (this.saveTimeout > 0)
        {
            this.saveTimeout --;
            if (this.saveTimeout == 0)
            {
                dumpState();
            }
        }
    }

    public void setChatErrorSnooper(ServerCommandSource source)
    {
        responsibleSource = source;
        errorSnooper = (expr, /*Nullable*/ token, message) ->
        {
            try
            {
                source.getPlayer();
            }
            catch (CommandSyntaxException e)
            {
                return null;
            }

            String shebang = message;
            if (expr.module != null)
            {
                shebang += " in " + expr.module.getName() + "";
            }
            else
            {
                shebang += " in system chat";
            }
            if (token != null)
            {
                String[] lines = expr.getCodeString().split("\n");

                if (lines.length > 1)
                {
                    shebang += " at line " + (token.lineno + 1) + ", pos " + (token.linepos + 1);
                }
                else
                {
                    shebang += " at pos " + (token.pos + 1);
                }
                Messenger.m(source, "r " + shebang);
                if (lines.length > 1 && token.lineno > 0)
                {
                    Messenger.m(source, "l " + lines[token.lineno - 1]);
                }
                Messenger.m(source, "l " + lines[token.lineno].substring(0, token.linepos), "r  HERE>> ", "l " +
                        lines[token.lineno].substring(token.linepos));
                if (lines.length > 1 && token.lineno < lines.length - 1)
                {
                    Messenger.m(source, "l " + lines[token.lineno + 1]);
                }
            }
            else
            {
                Messenger.m(source, "r " + shebang);
            }
            return new ArrayList<>();
        };
    }

    @Override
    public void resetErrorSnooper()
    {
        responsibleSource = null;
        super.resetErrorSnooper();
    }

    public void handleErrorWithStack(String intro, Exception exception)
    {
        if (responsibleSource != null)
        {
            if (exception instanceof CarpetExpressionException) ((CarpetExpressionException) exception).printStack(responsibleSource);
            String message = exception.getMessage();
            Messenger.m(responsibleSource, "r "+intro+(message.isEmpty()?"":": "+message));
        }
        else
        {
            CarpetSettings.LOG.error(intro+": "+exception.getMessage());
        }
    }

    @Override
    public void handleExpressionException(String message, ExpressionException exc)
    {
        handleErrorWithStack(message, new CarpetExpressionException(exc.getMessage(), exc.stack));
    }

    public CarpetScriptServer getScriptServer()
    {
        return scriptServer;
    }
}
