Note for when updating bootstrap-datetimepicker:

2018-03-28:
Fixed a bug in the blur handler that left key states unchanged instead of clearing them,
(the result of which caused keyBinds to not function properly if a modifier key was
pressed when the input lost focus.)