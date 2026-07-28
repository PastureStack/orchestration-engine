package io.cattle.platform.iaas.api.auth.dao;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.ProjectMember;
import io.cattle.platform.iaas.api.auth.projects.Member;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.util.TransformationService;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface AuthDao {

    Account getAdminAccount();

    Account getAccountById(Long id);

    Account getAccountByKeys(String access, String secretKey, TransformationService transformationService);

    Account getAccountByExternalId(String externalId, String externalType);

    Account getAccountByIdentityLink(String linkKey);

    Credential getIdentityLink(String linkKey);

    List<? extends Credential> getIdentityLinks(long accountId);

    Credential linkIdentity(Account account, Identity identity, String provider, String linkKey);

    Credential moveIdentityLink(Credential credential, Account sourceAccount, Account targetAccount,
                                long actorAccountId);

    void consumeIdentityProof(String proofKey, long actorAccountId);

    Credential createProviderSwitchTicket(Account account, String ticketKey, Map<String, Object> data);

    Credential consumeProviderSwitchTicket(String ticketKey, String provider);

    Credential consumeProviderSwitchTicket(String ticketKey, String provider, Long accountId,
                                           String externalIdType, String externalId);

    void cancelProviderSwitchTicket(String ticketKey);

    void recordIdentityLogin(Credential credential);

    int copyDirectProjectMemberships(Account sourceAccount, Account targetAccount);

    int removeDirectProjectMemberships(Account account);

    int countActiveAdminAccounts();

    Account getAccountByUuid(String uuid);

    Account createAccount(String name, String kind, String externalId, String externalType);

    Identity getIdentity(Long id, IdFormatter idFormatter);

    Identity getIdentityForDisplay(Long id, IdFormatter idFormatter);

    Account createProject(String name, String description);

    Account updateAccount(Account account, String name, String kind, String externalId, String externalType);

    List<Account> getAccessibleProjects(Set<Identity> identitySet, boolean isAdmin, Long usingAccount);

    boolean hasAccessToProject(long projectId, Long usingAccount, boolean isAdmin, Set<Identity> identitySet);

    boolean isProjectOwner(long projectId, Long usingAccount, boolean isAdmin, Set<Identity> identitySet);

    boolean isProjectMember(long projectId, Long usingAccount, boolean isAdmin, Set<Identity> identitySet);

    List<? extends ProjectMember> getActiveProjectMembers(long projectId);

    List<? extends ProjectMember> getProjectMembersByIdentity(long projectId, Set<Identity> identities);

    ProjectMember getProjectMember(long id);

    boolean hasAccessToAnyProject(Set<Identity> identities, boolean isAdmin, Long usingAccount);

    List<? extends ProjectMember> setProjectMembers(final Account project, final Set<Member> membersTransformed,
                                                    IdFormatter idFormatter);

    ProjectMember createProjectMember(Account project, Member member);

    void ensureAllProjectsHaveNonRancherIdMembers(Identity identity);

    List<Account> searchUsers(String name);

    Account getByUsername(String username);

    Account getAccountByLogin(String publicValue, String secretValue, TransformationService transformationService);

    String getRole(Account account, Policy policy, Policy authenticatedAsPolicy);

    Account getAccountByAccessKey(String accessKey);

    boolean canSetProjectMembers(long projectId, Long usingAccount, boolean isAdmin, Set<Identity> identities);
}
