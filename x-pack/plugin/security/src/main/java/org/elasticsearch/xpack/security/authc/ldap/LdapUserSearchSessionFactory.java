/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.ldap;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPInterface;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.security.authc.RealmConfig;
import org.elasticsearch.xpack.core.security.authc.RealmSettings;
import org.elasticsearch.xpack.core.security.authc.ldap.LdapUserSearchSessionFactorySettings;
import org.elasticsearch.xpack.core.security.authc.ldap.SearchGroupsResolverSettings;
import org.elasticsearch.xpack.core.security.authc.ldap.support.LdapSearchScope;
import org.elasticsearch.common.CharArrays;
import org.elasticsearch.xpack.core.ssl.SSLService;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapSession;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapSession.GroupsResolver;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils;

import static org.elasticsearch.xpack.core.security.authc.ldap.PoolingSessionFactorySettings.BIND_DN;
import static org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils.attributesToSearchFor;
import static org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils.createFilter;
import static org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils.searchForEntry;

class LdapUserSearchSessionFactory extends PoolingSessionFactory {

    static final String SEARCH_PREFIX = "user_search.";

    private final String userSearchBaseDn;
    private final LdapSearchScope scope;
    private final String searchFilter;

    LdapUserSearchSessionFactory(RealmConfig config, SSLService sslService, ThreadPool threadPool) throws LDAPException {
        super(config, sslService, groupResolver(config.settings()), LdapUserSearchSessionFactorySettings.POOL_ENABLED,
                BIND_DN.exists(config.settings()) ? BIND_DN.get(config.settings()) : null,
                () -> {
                    if (BIND_DN.exists(config.settings())) {
                        return BIND_DN.get(config.settings());
                    } else {
                        return LdapUserSearchSessionFactorySettings.SEARCH_BASE_DN.get(config.settings());
                    }
                }, threadPool);
        Settings settings = config.settings();
        if (LdapUserSearchSessionFactorySettings.SEARCH_BASE_DN.exists(settings)) {
            userSearchBaseDn = LdapUserSearchSessionFactorySettings.SEARCH_BASE_DN.get(settings);
        } else {
            throw new IllegalArgumentException("[" + RealmSettings.getFullSettingKey(config,
                    LdapUserSearchSessionFactorySettings.SEARCH_BASE_DN) + "] must be specified");
        }
        scope = LdapUserSearchSessionFactorySettings.SEARCH_SCOPE.get(settings);
        searchFilter = getSearchFilter(config);
        logger.info("Realm [{}] is in user-search mode - base_dn=[{}], search filter=[{}]",
                config.name(), userSearchBaseDn, searchFilter);
    }

    static boolean hasUserSearchSettings(RealmConfig config) {
        return config.settings().getByPrefix("user_search.").isEmpty() == false;
    }

    /**
     * Sets up a LDAPSession using the connection pool that potentially holds existing connections to the server
     */
    @Override
    void getSessionWithPool(LDAPConnectionPool connectionPool, String user, SecureString password, ActionListener<LdapSession> listener) {
        findUser(user, connectionPool, ActionListener.wrap((entry) -> {
            if (entry == null) {
                listener.onResponse(null);
            } else {
                final String dn = entry.getDN();
                final byte[] passwordBytes = CharArrays.toUtf8Bytes(password.getChars());
                final SimpleBindRequest bind = new SimpleBindRequest(dn, passwordBytes);
                LdapUtils.maybeForkThenBindAndRevert(connectionPool, bind, threadPool, new ActionRunnable<LdapSession>(listener) {
                    @Override
                    protected void doRun() throws Exception {
                        listener.onResponse(new LdapSession(logger, config, connectionPool, dn, groupResolver, metaDataResolver, timeout,
                                entry.getAttributes()));
                    }
                });
            }
        }, listener::onFailure));
    }

    /**
     * Sets up a LDAPSession using the following process:
     * <ol>
     *     <li>Opening a new connection to the LDAP server</li>
     *     <li>Executes a bind request using the bind user</li>
     *     <li>Executes a search to find the DN of the user</li>
     *     <li>Closes the opened connection</li>
     *     <li>Opens a new connection to the LDAP server</li>
     *     <li>Executes a bind request using the found DN and provided password</li>
     *     <li>Creates a new LDAPSession with the bound connection</li>
     * </ol>
     */
    @Override
    void getSessionWithoutPool(String user, SecureString password, ActionListener<LdapSession> listener) {
        try {
            final LDAPConnection connection = LdapUtils.privilegedConnect(serverSet::getConnection);
            LdapUtils.maybeForkThenBind(connection, bindCredentials, threadPool, new AbstractRunnable() {
                @Override
                protected void doRun() throws Exception {
                    findUser(user, connection, ActionListener.wrap((entry) -> {
                        if (entry == null) {
                            IOUtils.close(connection);
                            listener.onResponse(null);
                        } else {
                            final String dn = entry.getDN();
                            final byte[] passwordBytes = CharArrays.toUtf8Bytes(password.getChars());
                            final SimpleBindRequest userBind = new SimpleBindRequest(dn, passwordBytes);
                            LdapUtils.maybeForkThenBind(connection, userBind, threadPool, new AbstractRunnable() {
                                @Override
                                protected void doRun() throws Exception {
                                    LdapUtils.maybeForkThenBind(connection, bindCredentials, threadPool, new AbstractRunnable() {

                                        @Override
                                        protected void doRun() throws Exception {
                                            listener.onResponse(new LdapSession(logger, config, connection, dn, groupResolver,
                                                    metaDataResolver, timeout, entry.getAttributes()));
                                        }

                                        @Override
                                        public void onFailure(Exception e) {
                                            IOUtils.closeWhileHandlingException(connection);
                                            listener.onFailure(e);
                                        }
                                    });
                                }

                                @Override
                                public void onFailure(Exception e) {
                                    IOUtils.closeWhileHandlingException(connection);
                                    listener.onFailure(e);
                                }
                            });
                        }
                    }, e -> {
                        IOUtils.closeWhileHandlingException(connection);
                        listener.onFailure(e);
                    }));
                }
                @Override
                public void onFailure(Exception e) {
                    IOUtils.closeWhileHandlingException(connection);
                    listener.onFailure(e);
                }
            });
        } catch (LDAPException e) {
            listener.onFailure(e);
        }
    }

    @Override
    public boolean supportsUnauthenticatedSession() {
        return true;
    }

    @Override
    void getUnauthenticatedSessionWithPool(LDAPConnectionPool connectionPool, String user, ActionListener<LdapSession> listener) {
        findUser(user, connectionPool, ActionListener.wrap((entry) -> {
            if (entry == null) {
                listener.onResponse(null);
            } else {
                final String dn = entry.getDN();
                LdapSession session = new LdapSession(logger, config, connectionPool, dn, groupResolver, metaDataResolver, timeout,
                        entry.getAttributes());
                listener.onResponse(session);
            }
        }, listener::onFailure));
    }

    @Override
    void getUnauthenticatedSessionWithoutPool(String user, ActionListener<LdapSession> listener) {
        try {
            final LDAPConnection connection = LdapUtils.privilegedConnect(serverSet::getConnection);
            LdapUtils.maybeForkThenBind(connection, bindCredentials, threadPool, new AbstractRunnable() {
                @Override
                protected void doRun() throws Exception {
                    findUser(user, connection, ActionListener.wrap((entry) -> {
                        if (entry == null) {
                            IOUtils.close(connection);
                            listener.onResponse(null);
                        } else {
                            listener.onResponse(new LdapSession(logger, config, connection, entry.getDN(), groupResolver, metaDataResolver,
                                    timeout, entry.getAttributes()));
                        }
                    }, e -> {
                        IOUtils.closeWhileHandlingException(connection);
                        listener.onFailure(e);
                    }));
                }

                @Override
                public void onFailure(Exception e) {
                    IOUtils.closeWhileHandlingException(connection);
                    listener.onFailure(e);
                }
            });
        } catch (LDAPException e) {
            listener.onFailure(e);
        }
    }

    private void findUser(String user, LDAPInterface ldapInterface, ActionListener<SearchResultEntry> listener) {
        final Filter filter;
        try {
            filter = createFilter(searchFilter, user);
        } catch (LDAPException e) {
            listener.onFailure(e);
            return;
        }

        searchForEntry(ldapInterface, userSearchBaseDn, scope.scope(),
                filter, Math.toIntExact(timeout.seconds()), ignoreReferralErrors, listener,
                attributesToSearchFor(groupResolver.attributes(), metaDataResolver.attributeNames()));
    }

    private static GroupsResolver groupResolver(Settings settings) {
        if (SearchGroupsResolverSettings.BASE_DN.exists(settings)) {
            return new SearchGroupsResolver(settings);
        }
        return new UserAttributeGroupsResolver(settings);
    }

    static String getSearchFilter(RealmConfig config) {
        final Settings settings = config.settings();
        final boolean hasAttribute = LdapUserSearchSessionFactorySettings.SEARCH_ATTRIBUTE.exists(settings);
        final boolean hasFilter = LdapUserSearchSessionFactorySettings.SEARCH_FILTER.exists(settings);
        if (hasAttribute && hasFilter) {
            throw new IllegalArgumentException("search attribute setting [" +
                    RealmSettings.getFullSettingKey(config, LdapUserSearchSessionFactorySettings.SEARCH_ATTRIBUTE)
                    + "] and filter setting [" +
                    RealmSettings.getFullSettingKey(config, LdapUserSearchSessionFactorySettings.SEARCH_FILTER)
                    + "] cannot be combined!");
        } else if (hasFilter) {
            return LdapUserSearchSessionFactorySettings.SEARCH_FILTER.get(settings);
        } else if (hasAttribute) {
            return "(" + LdapUserSearchSessionFactorySettings.SEARCH_ATTRIBUTE.get(settings) + "={0})";
        } else {
            return "(uid={0})";
        }
    }


}