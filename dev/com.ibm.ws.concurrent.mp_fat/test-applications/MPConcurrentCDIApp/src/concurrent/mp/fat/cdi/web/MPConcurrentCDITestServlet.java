/*******************************************************************************
 * Copyright (c) 2018,2019 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package concurrent.mp.fat.cdi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.annotation.Annotation;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javax.annotation.Resource;
import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;
import javax.inject.Inject;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.annotation.WebServlet;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;

import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ManagedExecutorConfig;
import org.eclipse.microprofile.concurrent.NamedInstance;
import org.eclipse.microprofile.concurrent.ThreadContext;
import org.eclipse.microprofile.concurrent.ThreadContextConfig;
import org.junit.Test;
import org.test.context.location.CurrentLocation;

import componenttest.app.FATServlet;

@SuppressWarnings("serial")
@WebServlet(urlPatterns = "/MPConcurrentCDITestServlet")
public class MPConcurrentCDITestServlet extends FATServlet {

    static final int TIMEOUT_MIN = 2;

    @Inject
    ConcurrencyBean bean;

    @Inject
    BeanManager beanManager;

    @Inject
    RequestScopedBean requestBean;

    @Inject
    SessionScopedBean sessionBean;

    @Inject
    TransactionScopedBean txBean;

    @Inject
    ConversationScopeBean conversationBean;

    @Inject
    ManagedExecutor noAnno;

    @Inject
    ManagedExecutor noAnno2;

    @Inject
    @NamedInstance("defaultAnno")
    @ManagedExecutorConfig
    ManagedExecutor defaultAnno;

    @Inject
    @ManagedExecutorConfig(maxAsync = -1, maxQueued = -1, propagated = ThreadContext.ALL_REMAINING, cleared = ThreadContext.TRANSACTION)
    ManagedExecutor defaultAnnoVerbose;

    @Inject
    @NamedInstance("maxAsync5")
    @ManagedExecutorConfig(maxAsync = 5)
    ManagedExecutor maxAsync5;

    @Inject
    @NamedInstance("maxAsync5")
    ManagedExecutor maxAsync5Ref;

    @Inject
    @NamedInstance("max2")
    @ManagedExecutorConfig(maxAsync = 2, maxQueued = 2)
    ManagedExecutor max2;

    @Inject
    @ManagedExecutorConfig(propagated = {}, cleared = { ThreadContext.APPLICATION })
    ManagedExecutor noAppCtx;

    @Inject
    @ManagedExecutorConfig(propagated = { ThreadContext.APPLICATION, ThreadContext.TRANSACTION }, cleared = {})
    ManagedExecutor propagatedAB;

    @Inject
    @ManagedExecutorConfig
    ManagedExecutor defaultAnno1;

    @Inject
    @ManagedExecutorConfig
    ManagedExecutor defaultAnno2;

    @Inject
    @ManagedExecutorConfig(propagated = { ThreadContext.TRANSACTION, ThreadContext.APPLICATION }, cleared = {})
    ManagedExecutor propagatedBA;

    @Inject
    @ManagedExecutorConfig(propagated = { ThreadContext.CDI, ThreadContext.APPLICATION })
    ManagedExecutor propagateCDI;

    @Inject
    @ManagedExecutorConfig(propagated = {}, cleared = ThreadContext.ALL_REMAINING)
    ManagedExecutor propagatedNone;

    ManagedExecutor methodInjectedNoAnno;

    ManagedExecutor methodInjectedMax5;

    ManagedExecutor methodInjectedAnonymous;

    @Inject
    @NamedInstance("producerDefined")
    ManagedExecutor producerDefined;

    @Inject
    @ThreadContextConfig(propagated = "State", cleared = "City", unchanged = ThreadContext.ALL_REMAINING)
    ThreadContext threadContextWithConfig;

    @Inject
    @ThreadContextConfig
    ThreadContext threadContextWithDefaultConfig;

    @Inject
    ThreadContext threadContextWithDefaults;

    @Resource
    UserTransaction tx;

    @Inject
    @NamedInstance("namedThreadContext")
    ThreadContext threadContextWithName;

    @Inject
    @ThreadContextConfig(propagated = {}, cleared = ThreadContext.ALL_REMAINING)
    ThreadContext threadContextClearAll;

    @Inject
    @NamedInstance("namedThreadContext")
    @ThreadContextConfig(propagated = ThreadContext.APPLICATION, unchanged = "State", cleared = ThreadContext.ALL_REMAINING)
    ThreadContext threadContextWithNameAndConfig;

    @Inject
    public void setMethodInjectedNoAnno(ManagedExecutor me) {
        this.methodInjectedNoAnno = me;
    }

    @Inject
    public void setMethodInjectedMax5(@NamedInstance("maxAsync5") ManagedExecutor me) {
        this.methodInjectedMax5 = me;
    }

    @Inject
    public void setMethodInjectedAnonymous(@ManagedExecutorConfig(maxAsync = 5) ManagedExecutor me) {
        this.methodInjectedAnonymous = me;
    }

    /**
     * Use the BeanManager to find the bean for a ManagedExecutor produced by the container.
     * Verify that it has no EL name and that NamedInstance is listed as a qualifier, but not ManagedExecutorConfig.
     */
    @Test
    public void testBeanManagerLookupManagedExecutor() {
        NamedInstance.Literal namedInstance_max2 = AccessController
                        .doPrivileged((PrivilegedAction<NamedInstance.Literal>) () -> NamedInstance.Literal.of("max2"));
        Set<Bean<?>> beans = beanManager.getBeans(ManagedExecutor.class, namedInstance_max2);
        assertEquals(1, beans.size());
        Bean<?> b = beans.iterator().next();
        assertNull(b.getName()); // No EL name when @Named not present, per CDI spec 2.6.3 "Beans with no name"
        Set<Annotation> qualifiers = b.getQualifiers();
        NamedInstance namedInstance = null;
        for (Annotation anno : qualifiers)
            if (anno instanceof NamedInstance)
                namedInstance = (NamedInstance) anno;
            else if (!(anno instanceof Any))
                fail("Unexpected qualifier " + anno);
        assertEquals("max2", namedInstance.value());
    }

    /**
     * Use the BeanManager to find the bean for a ThreadContext produced by the container.
     * Verify that it has no EL name and that NamedInstance is listed as a qualifier, but not ThreadContextConfig.
     */
    @Test
    public void testBeanManagerLookupThreadContext() {
        NamedInstance.Literal namedInstance_namedThreadContext = AccessController
                        .doPrivileged((PrivilegedAction<NamedInstance.Literal>) () -> NamedInstance.Literal.of("namedThreadContext"));
        Set<Bean<?>> beans = beanManager.getBeans(ThreadContext.class, namedInstance_namedThreadContext);
        assertEquals(1, beans.size());
        Bean<?> b = beans.iterator().next();
        assertNull(b.getName()); // No EL name when @Named not present, per CDI spec 2.6.3 "Beans with no name"
        Set<Annotation> qualifiers = b.getQualifiers();
        NamedInstance namedInstance = null;
        for (Annotation anno : qualifiers)
            if (anno instanceof NamedInstance)
                namedInstance = (NamedInstance) anno;
            else if (!(anno instanceof Any))
                fail("Unexpected qualifier " + anno);
        assertEquals("namedThreadContext", namedInstance.value());
    }

    @Test
    public void testMEDefaultsNotEqual() {
        assertUnique(noAnno, noAnno2, bean.getNoAnno());
    }

    @Test
    public void testCDI_ME_Ctx_Propagate() throws Exception {
        checkCDIPropagation(true, "testCDI_ME_Ctx_Propagate-REQUEST", propagateCDI, requestBean);
        checkCDIPropagation(true, "testCDI_ME_Ctx_Propagate-SESSION", propagateCDI, sessionBean);
        checkCDIPropagation(true, "testCDI_ME_Ctx_Propagate-CONVERSATION", propagateCDI, conversationBean);
    }

    @Test
    public void testCDI_ME_Ctx_Clear() throws Exception {
        checkCDIPropagation(false, "testCDI_ME_Ctx_Clear-REQUEST", propagatedNone, requestBean);
        checkCDIPropagation(false, "testCDI_ME_Ctx_Clear-SESSION", propagatedNone, sessionBean);
        checkCDIPropagation(false, "testCDI_ME_Ctx_Clear-CONVERSATION", propagatedNone, conversationBean);
    }

    private void checkCDIPropagation(boolean expectPropagate, String stateToPropagate, ManagedExecutor me, AbstractBean bean) throws Exception {
        bean.setState(stateToPropagate);
        CompletableFuture<String> cf = me.supplyAsync(() -> {
            String state = bean.getState();
            System.out.println(stateToPropagate + " state=" + state);
            return state;
        });
        assertEquals(expectPropagate ? stateToPropagate : AbstractBean.UNINITIALIZED, cf.get(TIMEOUT_MIN, TimeUnit.MINUTES));
    }

    @Test
    public void testCDI_TC_Ctx_Propagate() throws Exception {
        requestBean.setState("testCDIContextPropagate-STATE2");
        Callable<String> getState = threadContextWithDefaultConfig.contextualCallable(() -> {
            String state = requestBean.getState();
            System.out.println("testCDIContextPropagate#2 state=" + state);
            return state;
        });
        assertEquals("testCDIContextPropagate-STATE2", getState.call());
    }

    @Test
    public void testCDI_TC_Ctx_Clear() throws Exception {
        ThreadContext clearAllCtx = ThreadContext.builder()
                        .propagated() // propagate nothing
                        .cleared(ThreadContext.ALL_REMAINING)
                        .build();

        requestBean.setState("testCDIThreadCtxClear-STATE1");

        Callable<String> getState = clearAllCtx.contextualCallable(() -> {
            String state = requestBean.getState();
            System.out.println("testCDIThreadCtxClear#1 state=" + state);
            return state;
        });
        assertEquals("UNINITIALIZED", getState.call());
    }

    @Test
    public void testMEConfiguredEqual() {
        // for @NamedInstance("maxAsnyc5")
        assertEquals(maxAsync5, bean.getMaxAsync5());
        assertEquals(maxAsync5, maxAsync5Ref);
        assertEquals(maxAsync5, methodInjectedMax5);

        // for @NamedInstance("defaultAnno")
        assertEquals(defaultAnno, bean.getDefaultAnno());

        // for @NamedInstance("producerDefined")
        assertEquals(producerDefined, bean.getProducerDefined());
    }

    @Test
    public void testMEDifferent() {
        assertUnique(defaultAnno, defaultAnnoVerbose, max2, maxAsync5, methodInjectedAnonymous,
                     noAnno, noAppCtx, producerDefined, propagatedAB, propagatedBA, bean.getMyQualifier());
    }

    @Test
    public void testAppDefinedQualifier() {
        assertNotNull(bean.getMyQualifier());
    }

    // @Test // TODO enable once Literal classes are updated for Java 2 security
    public void testProgrammaticCDILookup() {
        ManagedExecutor exec5a = CDI.current().select(ManagedExecutor.class, NamedInstance.Literal.of("maxAsync5")).get();
        assertNotNull(exec5a);

        ManagedExecutor exec5b = CDI.current().select(ManagedExecutor.class, NamedInstance.Literal.of("maxAsync5")).get();
        assertNotNull(exec5b);

        assertEquals(exec5a.toString(), exec5b.toString());

        ManagedExecutor max2 = CDI.current().select(ManagedExecutor.class, NamedInstance.Literal.of("max2")).get();
        assertNotNull(max2);

        assertFalse(exec5a.toString().equals(max2.toString()));
    }

    @Test
    public void testBasicMEWorks() throws Exception {
        CompletableFuture<String> cf = noAnno.supplyAsync(() -> {
            try {
                System.out.println("testBasicMEWorks: Performing lookup of 'foo'");
                // TODO: This confirms JEE context is on the thread, need to find an operation that
                // confirms 'application' context is on the thread
                return InitialContext.doLookup("foo");
            } catch (NamingException e) {
                e.printStackTrace();
                return e.getMessage();
            }
        });
        String result = cf.get(TIMEOUT_MIN, TimeUnit.MINUTES);
        System.out.println("testBasicMEWorks: result=" + result);
        assertEquals("bar", result);
    }

    // @Test
    // TODO: disable this test until I figure out a way to observe the absence
    // of application context, or the ability to wipe JEE context
    public void testNoAppContext() throws Exception {
        CompletableFuture<Boolean> cf1 = noAppCtx.supplyAsync(() -> {
            try {
                System.out.println("testNoAppContext: enter");
                InitialContext.doLookup("foo");
                fail("Should not be able to perform a JNDI lookup without application context.");
                return false;
            } catch (NamingException expected) {
                return true;
            }
        });
        assertEquals("Should not be able to perform JNDI lookup without app context",
                     true, cf1.get(TIMEOUT_MIN, TimeUnit.MINUTES));
    }

    /**
     * Using an executor configured with maxAsync=2 and maxQueued=2, use blocking tasks to fill up
     * the queue by submitting 2 tasks that run and block, then 2 tasks that sit in the queue. When
     * a 5th task is submitted it should be rejected because it exceeds the max queue size.
     */
    @Test
    public void testMaxQueueSizeExceededAndReject() throws Exception {
        CountDownLatch beginLatch = new CountDownLatch(2);
        CountDownLatch continueLatch = new CountDownLatch(1);

        // max concurrency: 2, max queue size: 2, runIfQueueFull: false
        CompletableFuture<Integer> cf0 = max2.supplyAsync(() -> 144);
        CompletableFuture<Integer> cf1, cf2, cf3, cf4, cf5, cf6;
        try {
            // Create 2 async stages that will block both max concurrency permits, and wait for both to start running
            cf1 = cf0.thenApplyAsync(new BlockableIncrementFunction("testMaxQueueSizeExceededAndReject1", beginLatch, continueLatch));
            cf2 = cf0.thenApplyAsync(new BlockableIncrementFunction("testMaxQueueSizeExceededAndReject2", beginLatch, continueLatch));
            assertTrue(beginLatch.await(TIMEOUT_MIN, TimeUnit.MINUTES));

            // Create 2 async stages to fill the queue
            cf3 = cf0.thenApplyAsync(new BlockableIncrementFunction("testMaxQueueSizeExceededAndReject3", null, null));
            cf4 = cf0.thenApplyAsync(new BlockableIncrementFunction("testMaxQueueSizeExceededAndReject4", null, null));

            // Attempt to create async stage which it will not be possible to submit due exceeding queue capacity
            cf5 = cf0.thenApplyAsync(new BlockableIncrementFunction("testMaxQueueSizeExceededAndReject5", null, null));
            try {
                Integer i = cf5.get(TIMEOUT_MIN, TimeUnit.MINUTES);
                fail("Should not be able to submit task for cf5. Instead result is: " + i);
            } catch (ExecutionException x) {
                if (x.getCause() instanceof RejectedExecutionException) {
                    String message = x.getCause().getMessage();
                    if (message == null
                        || !message.contains("CWWKE1201E")
                        || !message.contains("_MPConcurrentCDIApp_concurrent.mp.fat.cdi.web.MPConcurrentCDITestServlet/max2(maxAsync=2,maxQueued=2,cleared=[Transaction])")
                        || !message.contains("maxQueueSize")
                        || !message.contains(" 2")) // the maximum queue size
                        throw x;
                } else
                    throw x;
            }

            // Create an async stage that will be a delayed submit (after cf3 runs)
            cf6 = cf3.thenApplyAsync(new BlockableIncrementFunction("testMaxQueueSizeExceededAndReject6", null, null));

            // Confirm that asynchronous stages are not complete:
            try {
                cf3.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException x) {
            }

            assertFalse(cf1.isDone());
            assertFalse(cf2.isDone());
            assertFalse(cf3.isDone());
            assertFalse(cf4.isDone());
            assertTrue(cf5.isDone());
            assertTrue(cf5.isCompletedExceptionally());
            assertFalse(cf5.isCancelled());
            assertFalse(cf6.isDone());
        } finally {
            // Allow the async stages to complete
            continueLatch.countDown();
        }

        // Confirm that all asynchronous stages complete, once unblocked:
        assertEquals(Integer.valueOf(145), cf1.get(TIMEOUT_MIN, TimeUnit.MINUTES));
        assertEquals(Integer.valueOf(145), cf2.get(TIMEOUT_MIN, TimeUnit.MINUTES));
        assertEquals(Integer.valueOf(145), cf3.get(TIMEOUT_MIN, TimeUnit.MINUTES));
        assertEquals(Integer.valueOf(145), cf4.get(TIMEOUT_MIN, TimeUnit.MINUTES));
        assertEquals(Integer.valueOf(146), cf6.get(TIMEOUT_MIN, TimeUnit.MINUTES));

        assertTrue(cf1.isDone());
        assertTrue(cf2.isDone());
        assertTrue(cf3.isDone());
        assertTrue(cf4.isDone());
        assertTrue(cf6.isDone());

        assertFalse(cf1.isCompletedExceptionally());
        assertFalse(cf2.isCompletedExceptionally());
        assertFalse(cf3.isCompletedExceptionally());
        assertFalse(cf4.isCompletedExceptionally());
        assertFalse(cf6.isCompletedExceptionally());
    }

    private void assertUnique(ManagedExecutor... executors) {
        for (int i = 0; i < executors.length; i++)
            for (int j = i + 1; j < executors.length; j++)
                assertNotSame("Expected all instances to be unique, but index " + i + " and " + j + " were the same: " + Arrays.toString(executors),
                              executors[i], executors[j]);
    }

    /**
     * Verify that the container creates and injects an instance for a ThreadContext injection point
     * that is annotated with ThreadContextConfig. Verify that the created instance behaves according to
     * the specified configuration attributes of ThreadContextConfig.
     */
    @Test
    public void testThreadContextInjectedWithConfigAnnotation() throws Exception {
        // config: propagated = "State", cleared = "City", unchanged = ALL_REMAINING
        assertNotNull(threadContextWithConfig);

        tx.begin();
        try {
            CurrentLocation.setLocation("Oronoco", "Minnesota");

            Supplier<String> stateNameSupplier = threadContextWithConfig.contextualSupplier(() -> {
                try {
                    UserTransaction tx1 = InitialContext.doLookup("java:comp/UserTransaction");
                    assertEquals(Status.STATUS_ACTIVE, tx1.getStatus()); // unchanged
                } catch (NamingException | SystemException x) {
                    throw new CompletionException(x);
                }
                assertEquals("", CurrentLocation.getCity()); // cleared
                return CurrentLocation.getState(); // propagated
            });

            CurrentLocation.setLocation("Williston", "North Dakota");

            assertEquals("Minnesota", stateNameSupplier.get());
        } finally {
            CurrentLocation.clear();
            tx.commit();
        }
    }

    /**
     * Verify that the container creates and injects an instance for a ThreadContext injection point
     * that is annotated with ThreadContextConfig and the NamedInstance qualifier.
     * Verify that the created instance behaves according to the specified configuration attributes of
     * ThreadContextConfig.
     */
    @Test
    public void testThreadContextInjectedWithConfigAnnotationAndNamedInstanceQualifier() throws Exception {
        // config: propagated = APPLICATION, unchanged = "State", cleared = ALL_REMAINING
        assertNotNull(threadContextWithNameAndConfig);

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        tx.begin();
        try {
            CurrentLocation.setLocation("Eyota", "Minnesota");

            Callable<String> stateNameFinder = threadContextWithNameAndConfig.contextualCallable(() -> {
                UserTransaction tx2 = InitialContext.doLookup("java:comp/UserTransaction");
                tx2.begin(); // valid because prior transaction context is cleared (suspended) during task
                tx2.commit();
                assertEquals("", CurrentLocation.getCity()); // cleared
                // Should be possible to load application classes from class loader that is propagated by Application context
                Thread.currentThread().getContextClassLoader().loadClass(BlockableIncrementFunction.class.getName());
                return CurrentLocation.getState();
            });

            CurrentLocation.setLocation("Minot", "North Dakota");
            Thread.currentThread().setContextClassLoader(null);

            assertEquals("North Dakota", stateNameFinder.call());
        } finally {
            // restore context
            CurrentLocation.clear();
            tx.commit();
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    /**
     * Verify that NamedInstance behaves as a normal CDI qualifier when the injection point is not
     * annotated with ThreadContextConfig.
     */
    @Test
    public void testThreadContextInjectedWithNamedInstanceQualifier() throws Exception {
        // config: propagated = APPLICATION, unchanged = "State", cleared = ALL_REMAINING
        assertNotNull(threadContextWithName);

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        tx.begin();
        try {
            CurrentLocation.setLocation("Grand Forks", "North Dakota");
            Runnable task = threadContextWithName.contextualRunnable(() -> {
                try {
                    UserTransaction tx2 = InitialContext.doLookup("java:comp/UserTransaction");
                    tx2.begin(); // valid because prior transaction context is cleared (suspended) during task
                    tx2.commit();
                    // Should be possible to load application classes from class loader that is propagated by Application context
                    Thread.currentThread().getContextClassLoader().loadClass(BlockableIncrementFunction.class.getName());
                } catch (Exception x) {
                    throw new CompletionException(x);
                }
                assertEquals("", CurrentLocation.getCity()); // cleared
                assertEquals("Minnesota", CurrentLocation.getState()); // unchanged
                CurrentLocation.setLocation("Sioux Falls", "South Dakota");
            });

            CurrentLocation.setLocation("Vermillion", "Minnesota");
            Thread.currentThread().setContextClassLoader(null);

            task.run();

            assertEquals("Vermillion", CurrentLocation.getCity()); // restored after task
            assertEquals("South Dakota", CurrentLocation.getState()); // unchanged from task
        } finally {
            // restore context
            CurrentLocation.clear();
            tx.commit();
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    /**
     * Verify that the container creates and injects an instance for a ThreadContext injection point
     * that is neither annotated with ThreadContextConfig nor qualified with any qualifier annotations.
     * Verify that the created instance behaves according to the default configuration of ThreadContextConfig,
     * which is that Transaction context is cleared and all other known thread context types are propagated
     * to contextual tasks.
     */
    @Test
    public void testThreadContextInjectedWithoutConfigAnnotation() throws Exception {
        assertNotNull(threadContextWithDefaults);

        tx.begin();
        try {
            CurrentLocation.setLocation("Byron", "Minnesota");

            Callable<String> getLocationName = threadContextWithDefaults.contextualCallable(() -> {
                UserTransaction tx2 = InitialContext.doLookup("java:comp/UserTransaction");
                tx2.begin();
                try {
                    return CurrentLocation.getCity() + ", " + CurrentLocation.getState();
                } finally {
                    tx2.commit();
                }
            });

            CurrentLocation.setLocation("Bismarck", "North Dakota");

            assertEquals("Byron, Minnesota", getLocationName.call());

            assertEquals(Status.STATUS_ACTIVE, tx.getStatus());
        } finally {
            CurrentLocation.clear();
            tx.commit();
        }
    }

    /**
     * Verify that the container creates and injects an instance for a ThreadContext injection point
     * that is annotated with ThreadContextConfig without any parameters specified.
     * Verify that the created instance behaves according to the default configuration of ThreadContextConfig,
     * which is that Transaction context is cleared and all other known thread context types are propagated
     * to contextual tasks.
     */
    @Test
    public void testThreadContextInjectedWithUnconfiguredConfigAnnotation() throws Exception {
        assertNotNull(threadContextWithDefaultConfig);

        tx.begin();
        try {
            CurrentLocation.setLocation("Dodge Center", "Minnesota");

            Callable<String> getLocationName = threadContextWithDefaultConfig.contextualCallable(() -> {
                UserTransaction tx2 = InitialContext.doLookup("java:comp/UserTransaction");
                tx2.begin();
                try {
                    return CurrentLocation.getCity() + ", " + CurrentLocation.getState();
                } finally {
                    tx2.commit();
                }
            });

            CurrentLocation.setLocation("Fargo", "North Dakota");

            assertEquals("Dodge Center, Minnesota", getLocationName.call());

            assertEquals(Status.STATUS_ACTIVE, tx.getStatus());
        } finally {
            CurrentLocation.clear();
            tx.commit();
        }
    }

    /**
     * Verify that we disallow propagating global transactions, but do allow propagating the absence of any transaction.
     */
    @Test
    public void testTransactionContextPropagation() throws Exception {
        ManagedExecutor executor = propagatedAB; // propagates ThreadContext.TRANSACTION

        // valid to propagate empty transaction context
        CompletableFuture<Integer> cf1 = executor.newIncompleteFuture();
        CompletableFuture<Integer> cf2 = cf1.thenApply(i -> {
            try {
                return tx.getStatus();
            } catch (SystemException x) {
                throw new CompletionException(x);
            }
        });

        tx.begin();
        try {
            cf1.complete(50);
            assertEquals(Integer.valueOf(Status.STATUS_NO_TRANSACTION), cf2.get());

            assertEquals(Status.STATUS_ACTIVE, tx.getStatus());

            Future<?> f = executor.submit(() -> System.out.println("Should not be able to submit this task."));
            // TODO fail("Submitted task from within a transaction when transaction context propagation is enabled: " + f);
            f.get(TIMEOUT_MIN, TimeUnit.MINUTES);
        } catch (UnsupportedOperationException x) {
            if (x.getMessage() == null || !x.getMessage().startsWith("CWWKC1157E"))
                throw x;
        } finally {
            tx.commit();
        }

        // valid to propagate empty transaction context
        CompletableFuture<String> cf3 = cf2.thenApplyAsync(i -> "done");
        assertEquals("done", cf3.get(TIMEOUT_MIN, TimeUnit.MINUTES));
    }

    /**
     * Verify that TransactionScope beans reflect the propagation of an empty transaction context
     * and the restoration of the transaction context on the thread afterward.
     * Verify that the presence of CDI context propagation does not interfere.
     */
    @Test
    public void testTransactionScopeWithCDIContextPropagation() throws Exception {
        ThreadContext txAndCDIContext = ThreadContext.builder()
                        .propagated(ThreadContext.CDI, ThreadContext.TRANSACTION)
                        .cleared(ThreadContext.ALL_REMAINING)
                        .build();

        Runnable verifyContextNotActive = txAndCDIContext.contextualRunnable(() -> {
            try {
                String state = txBean.getState();
                throw new RuntimeException("TransactionScoped context should not be active when the absence of a transaction is propagated");
            } catch (ContextNotActiveException x) {
                // expected
            }
        });

        Callable<Boolean> updateStateWithinNewTransaction = txAndCDIContext.contextualCallable(() -> {
            tx.begin();
            try {
                assertEquals(AbstractBean.UNINITIALIZED, txBean.getState());
                txBean.setState("testTransactionScope-D");
                return true;
            } finally {
                tx.commit();
            }
        });

        tx.begin();
        try {
            txBean.setState("testTransactionScope-C");

            verifyContextNotActive.run();

            assertEquals("testTransactionScope-C", txBean.getState());

            assertEquals(Boolean.TRUE, updateStateWithinNewTransaction.call());

            assertEquals("testTransactionScope-C", txBean.getState());
        } finally {
            tx.commit();
        }
    }

    /**
     * Verify that TransactionScope beans reflect the propagation of an empty transaction context
     * and the restoration of the transaction context on the thread afterward.
     * Verify that the clearing of CDI context does not interfere.
     */
    @Test
    public void testTransactionScopeWithoutCDIContextPropagation() throws Exception {
        ManagedExecutor executor = propagatedAB; // propagates ThreadContext.TRANSACTION

        CompletableFuture<Boolean> readyToVerifyContextNotActive = executor.newIncompleteFuture();
        CompletableFuture<String> verifyContextNotActive = readyToVerifyContextNotActive.thenApply(b -> {
            try {
                return txBean.getState();
            } catch (ContextNotActiveException x) {
                return "ContextNotActiveException";
            }
        });

        CompletableFuture<Boolean> readyToUpdateState = executor.newIncompleteFuture();
        CompletableFuture<Boolean> updateStateWithinNewTransaction = readyToUpdateState.thenApply(b -> {
            try {
                tx.begin();
                try {
                    assertEquals(AbstractBean.UNINITIALIZED, txBean.getState());
                    txBean.setState("testTransactionScope-B");
                    return true;
                } finally {
                    tx.commit();
                }
            } catch (Exception x) {
                throw new CompletionException(x);
            }
        });

        tx.begin();
        try {
            txBean.setState("testTransactionScope-A");

            readyToVerifyContextNotActive.complete(true);
            assertEquals("ContextNotActiveException", verifyContextNotActive.join());

            assertEquals("testTransactionScope-A", txBean.getState());

            readyToUpdateState.complete(true);
            assertEquals(Boolean.TRUE, updateStateWithinNewTransaction.join());

            assertEquals("testTransactionScope-A", txBean.getState());
        } finally {
            tx.commit();
        }
    }
}
