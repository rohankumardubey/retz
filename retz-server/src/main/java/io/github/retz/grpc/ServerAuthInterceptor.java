package io.github.retz.grpc;

import io.github.retz.auth.AuthHeader;
import io.github.retz.auth.Authenticator;
import io.github.retz.db.Database;
import io.github.retz.protocol.data.User;
import io.grpc.*;
import io.netty.util.Constant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

public class ServerAuthInterceptor implements ServerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ServerAuthInterceptor.class);

    static final Metadata.Key<String> AUTH_HEADER_KEY =
            Metadata.Key.of(AuthHeader.AUTHORIZATION, Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> DATE_HEADER_KEY =
            Metadata.Key.of("Date", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        LOG.info("header: {} ? {} <<<", headers, headers.get(AUTH_HEADER_KEY));
        Optional<AuthHeader> maybeRemote = AuthHeader.parseHeaderValue(headers.get(AUTH_HEADER_KEY));
        AuthHeader remote = maybeRemote.get();
        LOG.info("key={}, date={}, signature={}, verb={}, resource={}", remote.key(), headers.get(DATE_HEADER_KEY), remote.signature(),
                call.getMethodDescriptor().getType().name(), call.getMethodDescriptor().getFullMethodName());
        //try {
            //Optional<User> user = Database.getInstance().getUser(remote.key());
            //Authenticator authenticator =
        // TODO: authenticate the client right here!!
        Context ctx = Context.current().withValue(RetzServer.USER_ID_KEY, remote.key());

            return Contexts.interceptCall(ctx, call, headers, next);
        //} catch (IOException e) {
          //  return null;
        //}
    }
}