import 'dart:convert';
import 'package:home_widget/home_widget.dart';
import 'package:mediatr/mediatr.dart';
import 'package:whph/core/domain/shared/utils/logger.dart';
import 'package:whph/core/application/features/tasks/queries/get_task_query.dart';
import 'package:whph/core/application/features/tasks/commands/save_task_command.dart';

/// Task completions queued by native widget taps (see WidgetTapReceiver.kt).
///
/// The native side ticks the row and records the task id immediately. This
/// class writes the completion to the database and clears the id. It runs
/// from the widget background callback and before every widget update, so a
/// tap is applied even if the background isolate was killed mid-way.
class WidgetPendingCompletions {
  static const String pendingKey = 'pending_task_completions';

  static Future<void>? _running;

  /// Completes all queued tasks. Concurrent callers share one run.
  static Future<void> process(Mediator mediator) {
    return _running ??= _process(mediator).whenComplete(() => _running = null);
  }

  static Future<List<String>> _readQueue() async {
    final raw = await HomeWidget.getWidgetData<String>(pendingKey);
    if (raw == null || raw.isEmpty) return [];
    try {
      return (jsonDecode(raw) as List).map((e) => e.toString()).toList();
    } catch (_) {
      return [];
    }
  }

  static Future<void> _process(Mediator mediator) async {
    final ids = await _readQueue();
    if (ids.isEmpty) return;

    final handled = <String>{};
    for (final id in ids) {
      try {
        await completeTask(mediator, id);
      } catch (e) {
        // Drop it anyway: the row un-ticks on the next update and can be tapped again.
        Logger.error('Failed to complete queued widget task $id: $e');
      }
      handled.add(id);
    }

    // Re-read so ids queued while we were working are kept.
    final remaining = (await _readQueue()).where((id) => !handled.contains(id)).toList();
    await HomeWidget.saveWidgetData<String>(pendingKey, remaining.isEmpty ? null : jsonEncode(remaining));
  }

  /// Marks a task complete. Does nothing if it is already complete, so repeat
  /// taps can never un-complete a task.
  static Future<void> completeTask(Mediator mediator, String taskId) async {
    final task = await mediator.send<GetTaskQuery, GetTaskQueryResponse>(GetTaskQuery(id: taskId));
    if (task.completedAt != null) return;

    await mediator.send<SaveTaskCommand, SaveTaskCommandResponse>(
      SaveTaskCommand(
        id: task.id,
        title: task.title,
        description: task.description,
        priority: task.priority,
        plannedDate: task.plannedDate,
        deadlineDate: task.deadlineDate,
        estimatedTime: task.estimatedTime,
        completedAt: DateTime.now().toUtc(),
        parentTaskId: task.parentTaskId,
        order: task.order,
        plannedDateReminderTime: task.plannedDateReminderTime,
        plannedDateReminderCustomOffset: task.plannedDateReminderCustomOffset,
        deadlineDateReminderTime: task.deadlineDateReminderTime,
        deadlineDateReminderCustomOffset: task.deadlineDateReminderCustomOffset,
        recurrenceType: task.recurrenceType,
        recurrenceInterval: task.recurrenceInterval,
        recurrenceStartDate: task.recurrenceStartDate,
        recurrenceEndDate: task.recurrenceEndDate,
        recurrenceCount: task.recurrenceCount,
        recurrenceParentId: task.recurrenceParentId,
        recurrenceConfiguration: task.recurrenceConfiguration,
      ),
    );
  }
}
