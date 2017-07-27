package cn.imaq.autumn.rpc.server.net;

import cn.imaq.autumn.rpc.exception.AutumnInvokeException;
import cn.imaq.autumn.rpc.exception.AutumnSerializationException;
import cn.imaq.autumn.rpc.net.AutumnRPCRequest;
import cn.imaq.autumn.rpc.net.AutumnRPCResponse;
import cn.imaq.autumn.rpc.serialization.AutumnSerialization;
import cn.imaq.autumn.rpc.serialization.JsonSerialization;
import cn.imaq.autumn.rpc.server.invoker.AutumnInvoker;
import cn.imaq.autumn.rpc.server.invoker.AutumnInvokerFactory;
import cn.imaq.autumn.rpc.server.invoker.AutumnMethod;
import cn.imaq.autumn.rpc.util.LogUtil;
import org.rapidoid.buffer.Buf;
import org.rapidoid.http.AbstractHttpServer;
import org.rapidoid.http.HttpResponseCodes;
import org.rapidoid.http.HttpStatus;
import org.rapidoid.http.MediaType;
import org.rapidoid.net.Server;
import org.rapidoid.net.abstracts.Channel;
import org.rapidoid.net.impl.RapidoidHelper;

import java.lang.reflect.InvocationTargetException;

import static cn.imaq.autumn.rpc.net.AutumnRPCResponse.STATUS_EXCEPTION;
import static cn.imaq.autumn.rpc.net.AutumnRPCResponse.STATUS_OK;

public class AutumnRPCHttpServer extends AbstractHttpServer {
    private final byte[] ROOT_PATH = new byte[]{'/'};
    private final byte[] METHOD_POST = "POST".getBytes();
    private final byte[] INFO_400 = "<html><head><title>400 Bad Request</title></head><body><center><h1>400 Bad Request</h1></center><hr><center>AutumnRPC</center></body></html>".getBytes();
    private final byte[] INFO_500 = "<html><head><title>500 Internal Server Error</title></head><body><center><h1>500 Internal Server Error</h1></center><hr><center>AutumnRPC</center></body></html>".getBytes();

    private final InstanceMap instanceMap = new InstanceMap();
    private AutumnInvoker invoker;
    private AutumnSerialization serialization;

    public AutumnRPCHttpServer() {
        super("AutumnRPC", "Not Found", "Internal Server Error", true);
    }

    public InstanceMap getInstanceMap() {
        return instanceMap;
    }

    public Server start(String host, int port) {
        this.invoker = AutumnInvokerFactory.getInvoker();
        this.serialization = new JsonSerialization(); // TODO config
        LogUtil.W("Using invoker: " + this.invoker.getClass().getSimpleName());
        return this.listen(host, port);
    }

    @Override
    protected HttpStatus handle(Channel ctx, Buf buf, RapidoidHelper req) {
        String verb = req.verb.str(buf);
        String path = req.path.str(buf);
        LogUtil.D("Received HTTP request: " + verb + " " + path);
        if (req.isGet.value) {
            if (matches(buf, req.path, ROOT_PATH)) {
                // TODO
            }
        }
        String[] paths = path.split("/");
        if (paths.length >= 2) {
            String serviceName = paths[1];
            Object instance = instanceMap.getInstance(serviceName);
            if (instance != null) {
                // parse request
                byte[] body = req.body.bytes(buf);
                try {
                    AutumnRPCRequest request = serialization.deserializeRequest(body);
                    try {
                        Object result = invoker.invoke(instance, new AutumnMethod(instance.getClass(), request.getMethodName(), request.getParamTypes()), request.getParams());
                        return ok(ctx, true, serialization.serializeResponse(
                                AutumnRPCResponse.builder().status(STATUS_OK).result(result).resultType(result.getClass()).build()
                        ), MediaType.JSON);
                    } catch (AutumnInvokeException e) {
                        LogUtil.E("Error invoking " + serviceName + "#" + request.getMethodName() + ": " + e.getCause());
                        return error(ctx);
                    } catch (InvocationTargetException e) {
                        LogUtil.E(serviceName + "#" + request.getMethodName() + " threw an exception: " + e.getCause());
                        return ok(ctx, true, serialization.serializeResponse(
                                AutumnRPCResponse.builder().status(STATUS_EXCEPTION).result(e.getCause()).resultType(e.getCause().getClass()).build()
                        ), MediaType.JSON);
                    }
                } catch (AutumnSerializationException e) {
                    LogUtil.E("Error parsing request: " + e.getClass().getSimpleName());
                }
            }
        }
        return badRequest(ctx);
    }

    private HttpStatus badRequest(Channel ctx) {
        ctx.write(HttpResponseCodes.get(400));
        writeCommonHeaders(ctx, false);
        writeBody(ctx, INFO_400, MediaType.HTML_UTF_8);
        return HttpStatus.DONE;
    }

    private HttpStatus error(Channel ctx) {
        ctx.write(HttpResponseCodes.get(500));
        writeCommonHeaders(ctx, false);
        writeBody(ctx, INFO_500, MediaType.HTML_UTF_8);
        return HttpStatus.DONE;
    }
}