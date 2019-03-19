/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.eth.sync.worldstate;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static tech.pegasys.pantheon.ethereum.eth.sync.worldstate.NodeDataRequest.createAccountDataRequest;

import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.BlockHeaderTestFixture;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.eth.manager.task.EthTask;
import tech.pegasys.pantheon.ethereum.storage.keyvalue.KeyValueStorageWorldStateStorage;
import tech.pegasys.pantheon.ethereum.worldstate.WorldStateStorage;
import tech.pegasys.pantheon.services.kvstore.InMemoryKeyValueStorage;
import tech.pegasys.pantheon.services.tasks.CachingTaskCollection;
import tech.pegasys.pantheon.services.tasks.InMemoryTaskQueue;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.concurrent.CompletableFuture;

import org.junit.Before;
import org.junit.Test;

public class WorldDownloadStateTest {

  private static final BytesValue ROOT_NODE_DATA = BytesValue.of(1, 2, 3, 4);
  private static final Hash ROOT_NODE_HASH = Hash.hash(ROOT_NODE_DATA);
  private static final int MAX_REQUESTS_WITHOUT_PROGRESS = 10;

  private final WorldStateStorage worldStateStorage =
      new KeyValueStorageWorldStateStorage(new InMemoryKeyValueStorage());

  private final BlockHeader header =
      new BlockHeaderTestFixture().stateRoot(ROOT_NODE_HASH).buildHeader();
  private final CachingTaskCollection<NodeDataRequest> pendingRequests =
      new CachingTaskCollection<>(new InMemoryTaskQueue<>());
  private final WorldStateDownloadProcess worldStateDownloadProcess =
      mock(WorldStateDownloadProcess.class);

  private final WorldDownloadState downloadState =
      new WorldDownloadState(pendingRequests, MAX_REQUESTS_WITHOUT_PROGRESS);

  private final CompletableFuture<Void> future = downloadState.getDownloadFuture();

  @Before
  public void setUp() {
    downloadState.setRootNodeData(ROOT_NODE_DATA);
    assertThat(downloadState.isDownloading()).isTrue();
  }

  @Test
  public void shouldCompleteReturnedFutureWhenNoPendingTasksRemain() {
    downloadState.checkCompletion(worldStateStorage, header);

    assertThat(future).isCompleted();
    assertThat(downloadState.isDownloading()).isFalse();
  }

  @Test
  public void shouldStoreRootNodeBeforeReturnedFutureCompletes() {
    final CompletableFuture<Void> postFutureChecks =
        future.thenAccept(
            result ->
                assertThat(worldStateStorage.getAccountStateTrieNode(ROOT_NODE_HASH))
                    .contains(ROOT_NODE_DATA));

    downloadState.checkCompletion(worldStateStorage, header);

    assertThat(future).isCompleted();
    assertThat(postFutureChecks).isCompleted();
  }

  @Test
  public void shouldNotCompleteWhenThereArePendingTasks() {
    pendingRequests.add(createAccountDataRequest(Hash.EMPTY_TRIE_HASH));

    downloadState.checkCompletion(worldStateStorage, header);

    assertThat(future).isNotDone();
    assertThat(worldStateStorage.getAccountStateTrieNode(ROOT_NODE_HASH)).isEmpty();
    assertThat(downloadState.isDownloading()).isTrue();
  }

  @Test
  public void shouldCancelOutstandingTasksWhenFutureIsCancelled() {
    final EthTask<?> outstandingTask1 = mock(EthTask.class);
    final EthTask<?> outstandingTask2 = mock(EthTask.class);
    downloadState.addOutstandingTask(outstandingTask1);
    downloadState.addOutstandingTask(outstandingTask2);

    pendingRequests.add(createAccountDataRequest(Hash.EMPTY_TRIE_HASH));
    pendingRequests.add(createAccountDataRequest(Hash.EMPTY));
    downloadState.setWorldStateDownloadProcess(worldStateDownloadProcess);

    future.cancel(true);

    verify(outstandingTask1).cancel();
    verify(outstandingTask2).cancel();

    assertThat(pendingRequests.isEmpty()).isTrue();
    verify(worldStateDownloadProcess).abort();
    assertThat(downloadState.isDownloading()).isFalse();
  }

  @Test
  public void shouldResetRequestsSinceProgressCountWhenProgressIsMade() {
    downloadState.requestComplete(false);
    downloadState.requestComplete(false);

    downloadState.requestComplete(true);

    for (int i = 0; i < MAX_REQUESTS_WITHOUT_PROGRESS - 1; i++) {
      downloadState.requestComplete(false);
      assertThat(downloadState.getDownloadFuture()).isNotDone();
    }

    downloadState.requestComplete(false);
    assertThat(downloadState.getDownloadFuture()).isCompletedExceptionally();
  }

  @Test
  public void shouldNotAddRequestsAfterDownloadIsStalled() {
    downloadState.checkCompletion(worldStateStorage, header);

    downloadState.enqueueRequests(singletonList(createAccountDataRequest(Hash.EMPTY_TRIE_HASH)));
    downloadState.enqueueRequest(createAccountDataRequest(Hash.EMPTY_TRIE_HASH));

    assertThat(pendingRequests.isEmpty()).isTrue();
  }
}