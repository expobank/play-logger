package play.modules.logger;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.PlayPlugin;
import play.mvc.Http;
import play.mvc.Scope;
import play.mvc.results.Error;
import play.mvc.results.*;
import play.rebel.RenderView;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.lang.System.nanoTime;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

public class RequestLogPlugin extends PlayPlugin {
  private static final String REQUEST_ID_PREFIX = Integer.toHexString((int)(Math.random() * 0x1000));
  private static final AtomicInteger counter = new AtomicInteger(1);
  static Logger logger = LoggerFactory.getLogger("request");

  private static final String LOG_AS_PATH = Play.configuration.getProperty("request.log.pathForAction", "Web.");
  
  @Override public void onConfigurationRead() {
    initMaskedParams();
  }

  @Override public void routeRequest(Http.Request request) {
    request.args.put("startTime", nanoTime());
    request.args.put("requestId", REQUEST_ID_PREFIX + "-" + counter.incrementAndGet());
  }

  @Override public void beforeActionInvocation(Method actionMethod) {
    Thread.currentThread().setName(String.format("%s %s [%s] (%s %s)",
      getOriginalThreadName(),
      Http.Request.current().action,
      Http.Request.current().args.get("requestId"),
      Http.Request.current().remoteAddress,
      (Scope.Session.current() == null ? "no-session" : Scope.Session.current().getId())));
  }

  private String getOriginalThreadName() {
    String name = Thread.currentThread().getName();
    int i = name.indexOf(' ');
    return i == -1 ? name : name.substring(0, i);
  }

  @Override public void onActionInvocationFinally() {
    Http.Request request = Http.Request.current();
    Result result = (Result) request.args.get("play.modules.logger.Result");
    if (!isAwait(request, result)) {
      logRequestInfo(request, Scope.Session.current(), result);
    }
    Thread.currentThread().setName(getOriginalThreadName());
  }

  @Override public void onActionInvocationResult(Result result) {
    Http.Request.current().args.put("play.modules.logger.Result", result);
  }

  public static void logRequestInfo(Http.Request request, Scope.Session session, Result result) {
    String executionTime = "";
    if (request.args != null) {
      Long start = (Long) request.args.get("startTime");
      if (start != null) executionTime = " " + TimeUnit.NANOSECONDS.toMillis(nanoTime() - start) + " ms";
    }

    logger.info(path(request) +
        ' ' + request.remoteAddress +
        ' ' + (session == null ? "no-session" : session.getId()) +
        getRequestLogCustomData(request) +
        ' ' + request.method +
        ' ' + extractParams(request) +
        " -> " + result(result) +
        executionTime);
  }

  private static boolean isAwait(Http.Request request, Result result) {
    return result == null && request.args.containsKey("__continuation");
  }

  static String result(Result result) {
    return result == null ? "RenderError" :
           result instanceof Redirect ? toString((Redirect) result) :
           result instanceof RenderTemplate ? toString((RenderTemplate) result) :
           result instanceof RenderView ? toString((RenderView) result) :
           result instanceof RenderBinary ? toString((RenderBinary) result) :
           result instanceof Error ? toString((Error) result) :
           result.getClass().getSimpleName();
  }

  private static String toString(Redirect result) {
    return result.getClass().getSimpleName() + ' ' + result.url;
  }

  private static String toString(RenderTemplate result) {
    return "RenderTemplate " + result.getName() + " " + result.getRenderTime() + " ms";
  }

  private static String toString(RenderView result) {
    return "RenderView " + result.getName() + " " + result.getRenderTime() + " ms";
  }

  private static String toString(RenderBinary result) {
    return Stream.of(result.getClass().getSimpleName(), result.getName(), result.getContentType())
        .filter(StringUtils::isNotEmpty)
        .collect(joining(" "));
  }

  private static String toString(Error result) {
    return result.getClass().getSimpleName() + " \"" + result.getMessage() + "\"";
  }

  static String getRequestLogCustomData(Http.Request request) {
    return request.args.containsKey("requestLogCustomData") ? " " + request.args.get("requestLogCustomData") : "";
  }

  private static String path(Http.Request request) {
    return request.action == null || request.action.startsWith(LOG_AS_PATH) ? request.path : request.action;
  }

  private static final Set<String> SKIPPED_PARAMS = new HashSet<>(asList("authenticityToken", "action", "controller", "x-http-method-override", "body", "action", "controller"));

  private static final Set<String> MASKED_PARAMS = new HashSet<>();

  private static void initMaskedParams() {
    String maskedParamsString = Play.configuration.getProperty("request.log.maskParams", "password|cvv|cardNumber|card.cvv|card.number");
    for (String param : maskedParamsString.split("\\|")) MASKED_PARAMS.add(param.toLowerCase().trim());
  }

  public static String extractParams(Http.Request request) {
    try {
      return extractParamsUnsafe(request);
    }
    catch (Exception e) {
      logger.error("Failed to parse request params", e);
      return "";
    }
  }

  private static String extractParamsUnsafe(Http.Request request) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String[]> param : request.params.all().entrySet()) {
      String name = param.getKey();
      if (SKIPPED_PARAMS.contains(name)) continue;
      sb.append('\t').append(name).append('=');
      String value;
      if (mustMask(name)) value = "*";
      else {
        if (param.getValue().length == 1)
          value = param.getValue()[0];
        else
          value = Arrays.toString(param.getValue());
      }
      sb.append(value);
    }
    return sb.toString().trim();
  }

  private static boolean mustMask(String name) {
    for (String maskedParam : MASKED_PARAMS) {
      if (name.toLowerCase().contains(maskedParam)) return true;
    }
    return false;
  }
}
