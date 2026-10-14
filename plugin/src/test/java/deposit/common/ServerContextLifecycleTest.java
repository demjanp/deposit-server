package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Proxy;
import org.neo4j.kernel.api.KernelTransaction;
import org.junit.jupiter.api.Test;

class ServerContextLifecycleTest {
    private static ServerContext initialized(UUID database)throws Exception {
        ServerContext server=ServerContext.attach(database);
        Field readiness=ServerContext.class.getDeclaredField("readiness");readiness.setAccessible(true);
        ((CompletableFuture<Void>)readiness.get(server)).complete(null);
        Field initialized=ServerContext.class.getDeclaredField("initialized");initialized.setAccessible(true);initialized.setBoolean(server,true);
        return server;
    }
    private static KernelTransaction transaction(long id) {return transaction(id,null);}
    private static KernelTransaction transaction(long id,UUID database) {
        return (KernelTransaction)Proxy.newProxyInstance(KernelTransaction.class.getClassLoader(),new Class<?>[]{KernelTransaction.class},(proxy,method,args)->switch(method.getName()){
            case "getTransactionSequenceNumber"->id;
            case "getDatabaseId"->database;
            case "toString"->"test transaction "+id;
            case "hashCode"->Long.hashCode(id);
            case "equals"->proxy==args[0];
            default->{Class<?> type=method.getReturnType();if(!type.isPrimitive())yield null;if(type==boolean.class)yield false;if(type==char.class)yield '\0';if(type==byte.class)yield (byte)0;if(type==short.class)yield (short)0;if(type==int.class)yield 0;if(type==long.class)yield 0L;if(type==float.class)yield 0f;yield 0d;}
        });
    }

    @SuppressWarnings("unchecked")
    @Test void shutdownRejectsRequestsAfterReadinessWasAlreadyPublished() throws Exception {
        UUID database=UUID.randomUUID();ServerContext server=initialized(database);
        try {
            server.requireReady();
            server.shutdown();
            IllegalStateException failure=assertThrows(IllegalStateException.class,server::requireReady);
            assertEquals("Deposit is shutting down",failure.getMessage());
        } finally {server.shutdown();ServerContext.detach(database);}
    }

    @Test void stopDuringInitializationReleasesReadinessWaiters()throws Exception {
        UUID database=UUID.randomUUID();ServerContext server=ServerContext.attach(database);CountDownLatch entered=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread waiter=Thread.ofPlatform().start(()->{entered.countDown();try{server.requireReady();}catch(Throwable e){failure.set(e);}});
        try {assertTrue(entered.await(1,TimeUnit.SECONDS));server.beginStop();waiter.join(2_000);assertFalse(waiter.isAlive());assertTrue(failure.get() instanceof IllegalStateException);}
        finally {waiter.interrupt();server.shutdown();ServerContext.detach(database);}
    }

    @SuppressWarnings("unchecked")
    @Test void admissionRacingStopIsEitherRegisteredOrRejected()throws Exception {
        for(int i=0;i<50;i++){
            long sequence=i;UUID database=UUID.randomUUID();ServerContext server=initialized(database);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<ServerContext.TxState> admitted=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> rejected=new java.util.concurrent.atomic.AtomicReference<>();
            Thread invocation=Thread.ofPlatform().start(()->{ready.countDown();try{go.await();admitted.set(server.admit(transaction(sequence)));}catch(Throwable e){rejected.set(e);}});
            Thread stop=Thread.ofPlatform().start(()->{ready.countDown();try{go.await();server.beginStop();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(ready.await(1,TimeUnit.SECONDS));go.countDown();invocation.join(2_000);stop.join(2_000);assertFalse(invocation.isAlive());assertFalse(stop.isAlive());
            Field transactions=ServerContext.class.getDeclaredField("transactions");transactions.setAccessible(true);var active=(java.util.Map<Long,ServerContext.TxState>)transactions.get(server);
            if(admitted.get()!=null){assertNull(rejected.get());assertSame(admitted.get(),active.get(sequence));}else{assertNotNull(rejected.get());assertTrue(active.isEmpty());}
            ServerContext.detach(database);
        }
    }

    @Test void shutdownWaitsForCompletionCallbacksAndResourceRelease()throws Exception {
        UUID database=UUID.randomUUID();ServerContext server=initialized(database);KernelTransaction tx=transaction(901,database);
        ServerContext.TxState state=server.admit(tx);CountDownLatch callbackEntered=new CountDownLatch(1),releaseCallback=new CountDownLatch(1);
        CountDownLatch releaseEntered=new CountDownLatch(1),releaseResource=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean stopped=new java.util.concurrent.atomic.AtomicBoolean();
        state.afterCommit.add(()->{callbackEntered.countDown();try{releaseCallback.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}});
        state.release.add(()->{releaseEntered.countDown();try{releaseResource.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}});
        Thread completion=Thread.ofPlatform().start(()->server.commit(tx));
        Thread shutdown=Thread.ofPlatform().start(()->{server.shutdown();stopped.set(true);});
        try {
            assertTrue(callbackEntered.await(2,TimeUnit.SECONDS));
            assertFalse(stopped.get());releaseCallback.countDown();
            assertTrue(releaseEntered.await(2,TimeUnit.SECONDS));Thread.sleep(100);
            assertFalse(stopped.get(),"shutdown must wait until completion callbacks and resource release finish");
            releaseResource.countDown();completion.join(2_000);shutdown.join(2_000);
            assertFalse(completion.isAlive());assertFalse(shutdown.isAlive());assertTrue(stopped.get());
        } finally {releaseCallback.countDown();releaseResource.countDown();completion.join(2_000);shutdown.join(2_000);ServerContext.detach(database);}
    }

    @Test void failedDrainCanBeRetriedAfterCompletion()throws Exception {
        UUID database=UUID.randomUUID();ServerContext server=initialized(database);KernelTransaction tx=transaction(902,database);
        server.admit(tx);
        try {
            assertThrows(IllegalStateException.class,()->server.shutdown(5,TimeUnit.MILLISECONDS));
            server.rollback(tx,new IllegalStateException("test rollback"));
            assertDoesNotThrow(()->server.shutdown(5,TimeUnit.MILLISECONDS));
            assertDoesNotThrow(()->server.shutdown());
        } finally {ServerContext.detach(database);}
    }

    @Test void cleanupAttemptsEveryReleaseAndRetainsFailedDrainObligation()throws Exception {
        UUID database=UUID.randomUUID();ServerContext server=initialized(database);KernelTransaction tx=transaction(903,database);
        ServerContext.TxState state=server.admit(tx);java.util.concurrent.atomic.AtomicBoolean laterRelease=new java.util.concurrent.atomic.AtomicBoolean();
        state.release.add(()->{throw new IllegalStateException("injected release failure");});
        state.release.add(()->laterRelease.set(true));
        try {
            server.commit(tx);
            assertTrue(laterRelease.get(),"a failed release must not skip later cleanup callbacks");
            assertThrows(IllegalStateException.class,()->server.shutdown(5,TimeUnit.MILLISECONDS));
            Field transactions=ServerContext.class.getDeclaredField("transactions");transactions.setAccessible(true);
            assertSame(state,((java.util.Map<Long,ServerContext.TxState>)transactions.get(server)).get(903L),"failed cleanup remains part of the drain barrier");
        } finally {ServerContext.detach(database);}
    }

    @Test void extensionDoesNotTreatTimedOutInitializerAsStopped()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Thread initializer=Thread.ofPlatform().start(()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){try{release.await();}catch(InterruptedException again){Thread.currentThread().interrupt();}}});
        try {
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->DepositExtension.awaitInitializer(initializer,10));
            assertTrue(initializer.isAlive());
        } finally {release.countDown();initializer.join(2_000);assertFalse(initializer.isAlive());}
    }
}
