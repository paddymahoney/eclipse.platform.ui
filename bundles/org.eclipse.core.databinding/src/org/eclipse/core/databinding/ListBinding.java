/*******************************************************************************
 * Copyright (c) 2007, 2017 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Matthew Hall - bugs 262221, 271148, 280341, 278550
 ******************************************************************************/

package org.eclipse.core.databinding;

import java.util.Collections;

import org.eclipse.core.databinding.observable.Diffs;
import org.eclipse.core.databinding.observable.ObservableTracker;
import org.eclipse.core.databinding.observable.list.IListChangeListener;
import org.eclipse.core.databinding.observable.list.IObservableList;
import org.eclipse.core.databinding.observable.list.ListDiff;
import org.eclipse.core.databinding.observable.list.ListDiffVisitor;
import org.eclipse.core.databinding.observable.value.IObservableValue;
import org.eclipse.core.databinding.observable.value.WritableValue;
import org.eclipse.core.internal.databinding.BindingStatus;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;

/**
 * @since 1.0
 *
 */
public class ListBinding extends Binding {

	private UpdateListStrategy targetToModel;
	private UpdateListStrategy modelToTarget;
	private IObservableValue validationStatusObservable;
	private boolean updatingTarget;
	private boolean updatingModel;

	private IListChangeListener targetChangeListener = event -> {
		if (!updatingTarget) {
			doUpdate((IObservableList) getTarget(), (IObservableList) getModel(), event.diff, targetToModel, false,
					false);
		}
	};
	private IListChangeListener modelChangeListener = event -> {
		if (!updatingModel) {
			doUpdate((IObservableList) getModel(), (IObservableList) getTarget(), event.diff, modelToTarget, false,
					false);
		}
	};

	/**
	 * @param target
	 * @param model
	 * @param modelToTargetStrategy
	 * @param targetToModelStrategy
	 */
	public ListBinding(IObservableList target, IObservableList model,
			UpdateListStrategy targetToModelStrategy,
			UpdateListStrategy modelToTargetStrategy) {
		super(target, model);
		this.targetToModel = targetToModelStrategy;
		this.modelToTarget = modelToTargetStrategy;
	}

	@Override
	public IObservableValue getValidationStatus() {
		return validationStatusObservable;
	}

	@Override
	protected void preInit() {
		ObservableTracker.setIgnore(true);
		try {
			validationStatusObservable = new WritableValue(context
					.getValidationRealm(), Status.OK_STATUS, IStatus.class);
		} finally {
			ObservableTracker.setIgnore(false);
		}
	}

	@Override
	protected void postInit() {
		if (modelToTarget.getUpdatePolicy() == UpdateListStrategy.POLICY_UPDATE) {
			getModel().getRealm().exec(() -> {
				((IObservableList) getModel()).addListChangeListener(modelChangeListener);
				updateModelToTarget();
			});
		} else {
			modelChangeListener = null;
		}

		if (targetToModel.getUpdatePolicy() == UpdateListStrategy.POLICY_UPDATE) {
			getTarget().getRealm().exec(() -> {
				((IObservableList) getTarget()).addListChangeListener(targetChangeListener);
				if (modelToTarget.getUpdatePolicy() == UpdateListStrategy.POLICY_NEVER) {
					// we have to sync from target to model, if the other
					// way round (model to target) is forbidden
					// (POLICY_NEVER)
					updateTargetToModel();
				} else {
					validateTargetToModel();
				}
			});
		} else {
			targetChangeListener = null;
		}
	}

	@Override
	public void updateModelToTarget() {
		final IObservableList modelList = (IObservableList) getModel();
		modelList.getRealm().exec(() -> {
			ListDiff diff = Diffs.computeListDiff(Collections.EMPTY_LIST, modelList);
			doUpdate(modelList, (IObservableList) getTarget(), diff, modelToTarget, true, true);
		});
	}

	@Override
	public void updateTargetToModel() {
		final IObservableList targetList = (IObservableList) getTarget();
		targetList.getRealm().exec(() -> {
			ListDiff diff = Diffs.computeListDiff(Collections.EMPTY_LIST, targetList);
			doUpdate(targetList, (IObservableList) getModel(), diff, targetToModel, true, true);
		});
	}

	@Override
	public void validateModelToTarget() {
		// nothing for now
	}

	@Override
	public void validateTargetToModel() {
		// nothing for now
	}

	/*
	 * This method may be moved to UpdateListStrategy in the future if clients
	 * need more control over how the two lists are kept in sync.
	 */
	private void doUpdate(final IObservableList source,
			final IObservableList destination, final ListDiff diff,
			final UpdateListStrategy updateListStrategy,
			final boolean explicit, final boolean clearDestination) {
		final int policy = updateListStrategy.getUpdatePolicy();
		if (policy != UpdateListStrategy.POLICY_NEVER) {
			if (policy != UpdateListStrategy.POLICY_ON_REQUEST || explicit) {
				destination.getRealm().exec(() -> {
					if (destination == getTarget()) {
						updatingTarget = true;
					} else {
						updatingModel = true;
					}
					final MultiStatus multiStatus = BindingStatus.ok();

					try {
						if (clearDestination) {
							destination.clear();
						}
						diff.accept(new ListDiffVisitor() {
							boolean useMoveAndReplace = updateListStrategy.useMoveAndReplace();

							@Override
							public void handleAdd(int index, Object element) {
								IStatus setterStatus = updateListStrategy.doAdd(destination,
										updateListStrategy.convert(element), index);

								mergeStatus(multiStatus, setterStatus);
							}

							@Override
							public void handleRemove(int index, Object element) {
								IStatus setterStatus = updateListStrategy.doRemove(destination, index);

								mergeStatus(multiStatus, setterStatus);
							}

							@Override
							public void handleMove(int oldIndex, int newIndex, Object element) {
								if (useMoveAndReplace) {
									IStatus setterStatus = updateListStrategy
											.doMove(destination, oldIndex, newIndex);

									mergeStatus(multiStatus, setterStatus);
								} else {
									super.handleMove(oldIndex, newIndex, element);
								}
							}

							@Override
							public void handleReplace(int index, Object oldElement, Object newElement) {
								if (useMoveAndReplace) {
									IStatus setterStatus = updateListStrategy
											.doReplace(destination, index, newElement);

									mergeStatus(multiStatus, setterStatus);
								} else {
									super.handleReplace(index, oldElement, newElement);
								}
							}
						});
						// TODO - at this point, the two lists will be out
						// of sync if an error occurred...
					} finally {
						setValidationStatus(multiStatus);

						if (destination == getTarget()) {
							updatingTarget = false;
						} else {
							updatingModel = false;
						}
					}
				});
			}
		}
	}

	private void setValidationStatus(final IStatus status) {
		validationStatusObservable.getRealm().exec(() -> validationStatusObservable.setValue(status));
	}

	/**
	 * Merges the provided <code>newStatus</code> into the
	 * <code>multiStatus</code>.
	 *
	 * @param multiStatus
	 * @param newStatus
	 */
	/* package */void mergeStatus(MultiStatus multiStatus, IStatus newStatus) {
		if (!newStatus.isOK()) {
			multiStatus.add(newStatus);
		}
	}

	@Override
	public void dispose() {
		if (targetChangeListener != null) {
			((IObservableList) getTarget())
					.removeListChangeListener(targetChangeListener);
			targetChangeListener = null;
		}
		if (modelChangeListener != null) {
			((IObservableList) getModel())
					.removeListChangeListener(modelChangeListener);
			modelChangeListener = null;
		}
		super.dispose();
	}
}
