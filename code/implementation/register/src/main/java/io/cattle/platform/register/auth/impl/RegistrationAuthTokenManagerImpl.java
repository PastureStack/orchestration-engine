package io.cattle.platform.register.auth.impl;

import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.register.auth.RegistrationAuthTokenManager;
import io.cattle.platform.register.dao.RegistrationTokenAuthDao;
import io.cattle.platform.register.util.RegistrationToken;

import java.util.Date;

import jakarta.inject.Inject;

public class RegistrationAuthTokenManagerImpl implements RegistrationAuthTokenManager {

    RegistrationTokenAuthDao authDao;
    ObjectManager objectManager;
    @Inject
    AccountDao accountDao;

    @Override
    public Account validateToken(String password) {
        if (password == null) {
            return null;
        }

        String[] parts = password.split(":", -1);

        if (parts.length != 3) {
            return null;
        }

        Date date = null;

        try {
            long time = Long.parseLong(parts[1]);

            if (System.currentTimeMillis() > (time + RegistrationToken.getAllowedTime())) {
                return null;
            }

            date = new Date(time);
        } catch (NumberFormatException e) {
            return null;
        }

        Credential cred = authDao.getCredential(parts[0]);

        if (cred == null) {
            return null;
        }

        if (!RegistrationToken.isValidToken(password, cred.getPublicValue(), cred.getSecretValue(), date)) {
            return null;
        }

        Account account = objectManager.loadResource(Account.class, cred.getAccountId());

        if (account == null || !accountDao.isActiveAccount(account) || account.getRemoved() != null) {
            return null;
        }

        return account;
    }

    public RegistrationTokenAuthDao getAuthDao() {
        return authDao;
    }

    @Inject
    public void setAuthDao(RegistrationTokenAuthDao authDao) {
        this.authDao = authDao;
    }

    public ObjectManager getObjectManager() {
        return objectManager;
    }

    @Inject
    public void setObjectManager(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }

}
