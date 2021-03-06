package edu.illinois.library.cantaloupe.auth;

import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.Key;
import edu.illinois.library.cantaloupe.image.Identifier;
import edu.illinois.library.cantaloupe.resource.RequestContext;
import edu.illinois.library.cantaloupe.script.DelegateProxy;
import edu.illinois.library.cantaloupe.script.DelegateProxyService;
import edu.illinois.library.cantaloupe.test.BaseTest;
import edu.illinois.library.cantaloupe.test.TestUtil;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AuthorizerFactoryTest extends BaseTest {

    private AuthorizerFactory instance;

    @Before
    public void setUp() {
        instance = new AuthorizerFactory();
    }

    @Test
    public void testNewAuthorizerWithNoArguments() {
        assertTrue(instance.newAuthorizer() instanceof PermissiveAuthorizer);
    }

    @Test
    public void testNewAuthorizerWithNullArguments() {
        assertTrue(instance.newAuthorizer(null, null) instanceof PermissiveAuthorizer);
    }

    @Test
    public void testNewAuthorizerWithArgument() throws Exception {
        Configuration config = Configuration.getInstance();
        config.setProperty(Key.DELEGATE_SCRIPT_ENABLED, true);
        config.setProperty(Key.DELEGATE_SCRIPT_PATHNAME,
                TestUtil.getFixture("delegates.rb").toString());

        RequestContext context = new RequestContext();
        context.setIdentifier(new Identifier("forbidden-code-no-reason.jpg"));
        DelegateProxyService service = DelegateProxyService.getInstance();
        DelegateProxy proxy = service.newDelegateProxy(context);

        assertTrue(instance.newAuthorizer(proxy) instanceof DelegateAuthorizer);
    }

}
