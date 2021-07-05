Note for when updating Select2:

2016-12-03:
When not using width=100% (as is common with Bootstrap) Select2 drop-downs
are not wide enough using the default calculation. To fix change:

In the source version, this is in the core.js file, in the function:
  Select2.prototype._resolveWidth = function ($element, method) {

and this line:
  return elementWidth + 'px';

is changed to this:
  return (elementWidth+24) + 'px';


2017-08-30:
Fix for selectOnClose option, causing it to only occur with the TAB key:

In core.js, this line:
        if (key === KEYS.ESC || key === KEYS.TAB ||

is replaced with this:
        if (key === KEYS.TAB) {
          self.options.set('okToSelectOnClose', true);
          self.close();

          evt.preventDefault();
          self.options.set('okToSelectOnClose', false);
        } else if (key === KEYS.ESC ||

Additionally, in selectOnClose.js, immediately after this function declaration:
  SelectOnClose.prototype._handleSelectOnClose = function (_, params) {

Add the following line:
    if( !this.options.get('okToSelectOnClose') ) return;


2017-08-31:
Added additional logic to more naturally handle (Shift) Tab keypresses,
causing focus to move to the next/previous element immediately.

20200408 DEJ:
If no options and/or options are loading ignore ENTER.
This avoids certain user error and makes it possible to use a barcode scanner.

20201105 DEJ:
Also ignore TAB if options are loading.
