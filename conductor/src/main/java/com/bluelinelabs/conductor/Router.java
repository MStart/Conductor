package com.bluelinelabs.conductor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.bluelinelabs.conductor.Controller.LifecycleListener;
import com.bluelinelabs.conductor.ControllerChangeHandler.ControllerChangeListener;
import com.bluelinelabs.conductor.changehandler.SimpleSwapChangeHandler;
import com.bluelinelabs.conductor.internal.NoOpControllerChangeHandler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A Router implements navigation and backstack handling for {@link Controller}s. Router objects are attached
 * to Activity/containing ViewGroup pairs. Routers do not directly render or push Views to the container ViewGroup,
 * but instead defer this responsibility to the {@link ControllerChangeHandler} specified in a given transaction.
 */
public abstract class Router {

    private static final String KEY_BACKSTACK = "Router.backstack";
    private static final String KEY_POPS_LAST_VIEW = "Router.popsLastView";

    protected final Backstack mBackStack = new Backstack();
    private final List<ControllerChangeListener> mChangeListeners = new ArrayList<>();
    private final List<Controller> mDestroyingControllers = new ArrayList<>();

    private boolean mPopsLastView = false;

    ViewGroup mContainer;

    /**
     * Returns this Router's host Activity
     */
    public abstract Activity getActivity();

    /**
     * This should be called by the host Activity when its onActivityResult method is called if the instanceId
     * of the controller that called startActivityForResult is not known.
     *
     * @param requestCode The Activity's onActivityResult requestCode
     * @param resultCode The Activity's onActivityResult resultCode
     * @param data The Activity's onActivityResult data
     */
    public abstract void onActivityResult(int requestCode, int resultCode, Intent data);

    /**
     * This should be called by the host Activity when its onRequestPermissionsResult method is called. The call will be forwarded
     * to the {@link Controller} with the instanceId passed in.
     *
     * @param instanceId The instanceId of the Controller to which this result should be forwarded
     * @param requestCode The Activity's onRequestPermissionsResult requestCode
     * @param permissions The Activity's onRequestPermissionsResult permissions
     * @param grantResults The Activity's onRequestPermissionsResult grantResults
     */
    public void onRequestPermissionsResult(String instanceId, int requestCode, String[] permissions, int[] grantResults) {
        Controller controller = getControllerWithInstanceId(instanceId);
        if (controller != null) {
            controller.requestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * This should be called by the host Activity when its onBackPressed method is called. The call will be forwarded
     * to its top {@link Controller}. If that controller doesn't handle it, then it will be popped.
     */
    public boolean handleBack() {
        if (!mBackStack.isEmpty()) {
            if (mBackStack.peek().controller.handleBack()) {
                return true;
            } else if (popCurrentController()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Pops the top {@link Controller} from the backstack
     *
     * @return Whether or not this Router still has controllers remaining on it after popping.
     */
    public boolean popCurrentController() {
        return popController(mBackStack.peek().controller);
    }

    /**
     * Pops the passed {@link Controller} from the backstack
     *
     * @param controller The controller that should be popped from this Router
     * @return Whether or not this Router still has controllers remaining on it after popping.
     */
    public boolean popController(Controller controller) {
        RouterTransaction topController = mBackStack.peek();
        boolean poppingTopController = topController != null && topController.controller == controller;

        if (poppingTopController) {
            trackDestroyingController(mBackStack.pop());
        } else {
            for (RouterTransaction transaction : mBackStack) {
                if (transaction.controller == controller) {
                    mBackStack.remove(transaction);
                    break;
                }
            }
        }

        if (poppingTopController) {
            performControllerChange(mBackStack.peek(), topController, false);
        }

        if (mPopsLastView) {
            return topController != null;
        } else {
            return !mBackStack.isEmpty();
        }
    }

    /**
     * Pushes a new {@link Controller} to the backstack
     *
     * @param transaction The transaction detailing what should be pushed, including the {@link Controller},
     *                    and its push and pop {@link ControllerChangeHandler}, and its tag.
     */
    public void pushController(@NonNull RouterTransaction transaction) {
        RouterTransaction from = mBackStack.peek();
        pushToBackstack(transaction);
        performControllerChange(transaction, from, true);
    }

    /**
     * Replaces this Router's top {@link Controller} with a new {@link Controller}
     *
     * @param transaction The transaction detailing what should be pushed, including the {@link Controller},
     *                    and its push and pop {@link ControllerChangeHandler}, and its tag.
     */
    public void replaceTopController(@NonNull RouterTransaction transaction) {
        RouterTransaction topTransaction = mBackStack.peek();
        if (!mBackStack.isEmpty()) {
            trackDestroyingController(mBackStack.pop());
        }

        pushToBackstack(transaction);
        performControllerChange(transaction, topTransaction, true);
    }

    void destroy() {
        mPopsLastView = true;
        List<RouterTransaction> poppedControllers = mBackStack.popAll();

        if (poppedControllers.size() > 0) {
            trackDestroyingControllers(poppedControllers);

            performControllerChange(null, poppedControllers.get(0).controller, false, poppedControllers.get(0).popChangeHandler());
        }
    }

    //TODO: this needs a better name and some docs
    public Router setPopsLastView(boolean popsLastView) {
        mPopsLastView = popsLastView;
        return this;
    }

    /**
     * Pops all {@link Controller}s until only the root is left
     *
     * @return Whether or not any {@link Controller}s were popped in order to get to the root transaction
     */
    public boolean popToRoot() {
        return popToRoot(null);
    }

    /**
     * Pops all {@link Controller} until only the root is left
     *
     * @param changeHandler The {@link ControllerChangeHandler} to handle this transaction
     * @return Whether or not any {@link Controller}s were popped in order to get to the root transaction
     */
    public boolean popToRoot(ControllerChangeHandler changeHandler) {
        if (mBackStack.size() > 1) {
            popToTransaction(mBackStack.root(), changeHandler);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Pops all {@link Controller}s until the Controller with the passed tag is at the top
     *
     * @param tag The tag being popped to
     * @return Whether or not any {@link Controller}s were popped in order to get to the transaction with the passed tag
     */
    public boolean popToTag(@NonNull String tag) {
        return popToTag(tag, null);
    }

    /**
     * Pops all {@link Controller}s until the {@link Controller} with the passed tag is at the top
     *
     * @param tag The tag being popped to
     * @param changeHandler The {@link ControllerChangeHandler} to handle this transaction
     * @return Whether or not the {@link Controller} with the passed tag is now at the top
     */
    public boolean popToTag(@NonNull String tag, ControllerChangeHandler changeHandler) {
        for (RouterTransaction transaction : mBackStack) {
            if (tag.equals(transaction.tag())) {
                popToTransaction(transaction, changeHandler);
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the root Controller. If any {@link Controller}s are currently in the backstack, they will be removed.
     *
     * @param transaction The transaction detailing what should be pushed, including the {@link Controller},
     *                    and its push and pop {@link ControllerChangeHandler}, and its tag.
     */
    public void setRoot(@NonNull RouterTransaction transaction) {
        RouterTransaction currentTop = mBackStack.peek();
        removeAllExceptTopAndUnowned();

        trackDestroyingControllers(mBackStack.popAll());

        pushToBackstack(transaction);
        performControllerChange(transaction, currentTop, true);
    }

    /**
     * Returns the hosted Controller with the given instance id, if available.
     *
     * @param instanceId The instance ID being searched for
     * @return The matching Controller, if one exists
     */
    public Controller getControllerWithInstanceId(String instanceId) {
        for (RouterTransaction transaction : mBackStack) {
            Controller controllerWithId = transaction.controller.findController(instanceId);
            if (controllerWithId != null) {
                return controllerWithId;
            }
        }
        return null;
    }

    /**
     * Returns the hosted Controller that was pushed with the given tag, if available.
     *
     * @param tag The tag being searched for
     * @return The matching Controller, if one exists
     */
    public Controller getControllerWithTag(String tag) {
        for (RouterTransaction transaction : mBackStack) {
            if (tag.equals(transaction.tag())) {
                return transaction.controller;
            }
        }
        return null;
    }

    /**
     * Returns the number of {@link Controller}s currently in the backstack
     */
    public int getBackstackSize() {
        return mBackStack.size();
    }

    /**
     * Returns the current backstack, ordered from root to most recently pushed.
     */
    public List<RouterTransaction> getBackstack() {
        List<RouterTransaction> list = new ArrayList<>();
        Iterator<RouterTransaction> backstackIterator = mBackStack.reverseIterator();
        while (backstackIterator.hasNext()) {
            list.add(backstackIterator.next());
        }
        return list;
    }

    /**
     * Sets the backstack, transitioning from the current top controller to the top of the new stack (if different)
     * using the passed {@link ControllerChangeHandler}
     *
     * @param backstack The new backstack
     * @param changeHandler An optional change handler to be used to handle the transition
     */
    public void setBackstack(@NonNull List<RouterTransaction> backstack, ControllerChangeHandler changeHandler) {
        RouterTransaction oldTopTransaction = mBackStack.peek();
        Controller oldTop = oldTopTransaction != null ? oldTopTransaction.controller : null;
        removeAllExceptTopAndUnowned();

        if (backstack.size() > 0) {
            Controller newTop = backstack.get(backstack.size() - 1).controller;

            if (newTop != oldTop) {
                ControllerChangeHandler handler = changeHandler != null ? changeHandler : new SimpleSwapChangeHandler();
                performControllerChange(newTop, oldTop, true, handler);
            }
        }

        for (RouterTransaction transaction : backstack) {
            transaction.onAttachedToRouter();
        }

        mBackStack.setBackstack(backstack);
    }

    /**
     * Returns whether or not this Router has a root {@link Controller}
     */
    public boolean hasRootController() {
        return getBackstackSize() > 0;
    }

    /**
     * Adds a listener for all of this Router's {@link Controller} change events
     *
     * @param changeListener The listener
     */
    public void addChangeListener(ControllerChangeListener changeListener) {
        if (!mChangeListeners.contains(changeListener)) {
            mChangeListeners.add(changeListener);
        }
    }

    /**
     * Removes a previously added listener
     *
     * @param changeListener The listener to be removed
     */
    public void removeChangeListener(ControllerChangeListener changeListener) {
        mChangeListeners.remove(changeListener);
    }

    /**
     * Attaches this Router's existing backstack to its container if one exists.
     */
    public void rebindIfNeeded() {
        Iterator<RouterTransaction> backstackIterator = mBackStack.reverseIterator();
        while (backstackIterator.hasNext()) {
            RouterTransaction transaction = backstackIterator.next();

            if (transaction.controller.getNeedsAttach()) {
                performControllerChange(transaction.controller, null, true, new SimpleSwapChangeHandler(false));
            }
        }
    }

    public final void onActivityResult(String instanceId, int requestCode, int resultCode, Intent data) {
        Controller controller = getControllerWithInstanceId(instanceId);
        if (controller != null) {
            controller.onActivityResult(requestCode, resultCode, data);
        }
    }

    public final void onActivityStarted(Activity activity) {
        for (RouterTransaction transaction : mBackStack) {
            transaction.controller.activityStarted(activity);

            for (Router childRouter : transaction.controller.getChildRouters()) {
                childRouter.onActivityStarted(activity);
            }
        }
    }

    public final void onActivityResumed(Activity activity) {
        for (RouterTransaction transaction : mBackStack) {
            transaction.controller.activityResumed(activity);

            for (Router childRouter : transaction.controller.getChildRouters()) {
                childRouter.onActivityResumed(activity);
            }
        }
    }

    public final void onActivityPaused(Activity activity) {
        for (RouterTransaction transaction : mBackStack) {
            transaction.controller.activityPaused(activity);

            for (Router childRouter : transaction.controller.getChildRouters()) {
                childRouter.onActivityPaused(activity);
            }
        }
    }

    public final void onActivityStopped(Activity activity) {
        for (RouterTransaction transaction : mBackStack) {
            transaction.controller.activityStopped(activity);

            for (Router childRouter : transaction.controller.getChildRouters()) {
                childRouter.onActivityStopped(activity);
            }
        }
    }

    public void onActivityDestroyed(Activity activity) {
        prepareForContainerRemoval();
        mChangeListeners.clear();

        for (RouterTransaction transaction : mBackStack) {
            transaction.controller.activityDestroyed(activity.isChangingConfigurations());

            for (Router childRouter : transaction.controller.getChildRouters()) {
                childRouter.onActivityDestroyed(activity);
            }
        }

        for (int index = mDestroyingControllers.size() - 1; index >= 0; index--) {
            Controller controller = mDestroyingControllers.get(index);
            controller.activityDestroyed(activity.isChangingConfigurations());

            for (Router childRouter : controller.getChildRouters()) {
                childRouter.onActivityDestroyed(activity);
            }
        }

        mContainer = null;
    }

    public void prepareForHostDetach() {
        for (RouterTransaction transaction : mBackStack) {
            transaction.controller.prepareForHostDetach();
        }
    }

    public void saveInstanceState(Bundle outState) {
        prepareForHostDetach();

        Bundle backstackState = new Bundle();
        mBackStack.saveInstanceState(backstackState);

        outState.putParcelable(KEY_BACKSTACK, backstackState);
        outState.putBoolean(KEY_POPS_LAST_VIEW, mPopsLastView);
    }

    public void restoreInstanceState(Bundle savedInstanceState) {
        Bundle backstackBundle = savedInstanceState.getParcelable(KEY_BACKSTACK);
        mBackStack.restoreInstanceState(backstackBundle);
        mPopsLastView = savedInstanceState.getBoolean(KEY_POPS_LAST_VIEW);

        Iterator<RouterTransaction> backstackIterator = mBackStack.reverseIterator();
        while (backstackIterator.hasNext()) {
            setControllerRouter(backstackIterator.next().controller);
        }
    }

    public final void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        for (RouterTransaction transaction : mBackStack) {
            transaction.controller.createOptionsMenu(menu, inflater);

            for (Router childRouter : transaction.controller.getChildRouters()) {
                childRouter.onCreateOptionsMenu(menu, inflater);
            }
        }
    }

    public final void onPrepareOptionsMenu(Menu menu) {
        for (RouterTransaction transaction : mBackStack) {
            transaction.controller.prepareOptionsMenu(menu);

            for (Router childRouter : transaction.controller.getChildRouters()) {
                childRouter.onPrepareOptionsMenu(menu);
            }
        }
    }

    public final boolean onOptionsItemSelected(MenuItem item) {
        for (RouterTransaction transaction : mBackStack) {
            if (transaction.controller.optionsItemSelected(item)) {
                return true;
            }

            for (Router childRouter : transaction.controller.getChildRouters()) {
                if (childRouter.onOptionsItemSelected(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void popToTransaction(@NonNull RouterTransaction transaction, ControllerChangeHandler changeHandler) {
        RouterTransaction topTransaction = mBackStack.peek();
        List<RouterTransaction> poppedTransactions = mBackStack.popTo(transaction);
        trackDestroyingControllers(poppedTransactions);

        if (poppedTransactions.size() > 0) {
            if (changeHandler == null) {
                changeHandler = topTransaction.popChangeHandler();
            }

            performControllerChange(mBackStack.peek().controller, topTransaction.controller, false, changeHandler);
        }
    }

    void prepareForContainerRemoval() {
        if (mContainer != null) {
            mContainer.setOnHierarchyChangeListener(null);
        }
    }

    final List<Controller> getControllers() {
        List<Controller> controllers = new ArrayList<>();

        Iterator<RouterTransaction> backstackIterator = mBackStack.reverseIterator();
        while (backstackIterator.hasNext()) {
            controllers.add(backstackIterator.next().controller);
        }

        return controllers;
    }

    public final Boolean handleRequestedPermission(@NonNull String permission) {
        for (RouterTransaction transaction : mBackStack) {
            if (transaction.controller.didRequestPermission(permission)) {
                return transaction.controller.shouldShowRequestPermissionRationale(permission);
            }
        }
        return null;
    }

    private void performControllerChange(RouterTransaction to, RouterTransaction from, boolean isPush) {
        if (isPush && to != null) {
            to.onAttachedToRouter();
        }

        ControllerChangeHandler changeHandler;
        if (isPush) {
            //noinspection ConstantConditions
            changeHandler = to.pushChangeHandler();
        } else if (from != null) {
            changeHandler = from.popChangeHandler();
        } else {
            changeHandler = new SimpleSwapChangeHandler();
        }

        Controller toController = to != null ? to.controller : null;
        Controller fromController = from != null ? from.controller : null;

        performControllerChange(toController, fromController, isPush, changeHandler);
    }

    private void performControllerChange(final Controller to, final Controller from, boolean isPush, @NonNull ControllerChangeHandler changeHandler) {
        if (to != null) {
            setControllerRouter(to);
        } else if (mBackStack.size() == 0 && !mPopsLastView) {
            // We're emptying out the backstack. Views get weird if you transition them out, so just no-op it. The hosting
            // Activity should be handling this by finishing or at least hiding this view.
            changeHandler = new NoOpControllerChangeHandler();
        }

        ControllerChangeHandler.executeChange(to, from, isPush, mContainer, changeHandler, mChangeListeners);
    }

    void pushToBackstack(@NonNull RouterTransaction entry) {
        mBackStack.push(entry);
    }

    private void trackDestroyingController(RouterTransaction transaction) {
        if (!transaction.controller.isDestroyed()) {
            mDestroyingControllers.add(transaction.controller);

            transaction.controller.addLifecycleListener(new LifecycleListener() {
                @Override
                public void postDestroy(@NonNull Controller controller) {
                    mDestroyingControllers.remove(controller);
                }
            });
        }
    }

    private void trackDestroyingControllers(List<RouterTransaction> transactions) {
        for (RouterTransaction transaction : transactions) {
            trackDestroyingController(transaction);
        }
    }

    private void removeAllExceptTopAndUnowned() {
        List<View> views = new ArrayList<>();

        RouterTransaction topTransaction = mBackStack.peek();
        final View topView = topTransaction != null ? topTransaction.controller.getView() : null;
        if (topView != null) {
            views.add(topView);
        }

        for (Router router : getSiblingRouters()) {
            if (router.mContainer == mContainer) {
                addRouterViewsToList(router, views);
            }
        }

        final int childCount = mContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = mContainer.getChildAt(i);
            if (!views.contains(child)) {
                mContainer.removeView(child);
            }
        }
    }

    private void addRouterViewsToList(Router router, List<View> list) {
        for (Controller controller : router.getControllers()) {
            if (controller.getView() != null) {
                list.add(controller.getView());
            }

            for (Router child : controller.getChildRouters()) {
                addRouterViewsToList(child, list);
            }
        }
    }

    void setControllerRouter(Controller controller) {
        controller.setRouter(this);
    }

    abstract void invalidateOptionsMenu();
    abstract void startActivity(Intent intent);
    abstract void startActivityForResult(String instanceId, Intent intent, int requestCode);
    abstract void startActivityForResult(String instanceId, Intent intent, int requestCode, Bundle options);
    abstract void registerForActivityResult(String instanceId, int requestCode);
    abstract void unregisterForActivityResults(String instanceId);
    abstract void requestPermissions(String instanceId, String[] permissions, int requestCode);
    abstract boolean hasHost();
    abstract List<Router> getSiblingRouters();
}
