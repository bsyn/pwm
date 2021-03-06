import { IScope } from 'angular';
import Column from './../models/column.model';

export default class TableDirectiveController {
    columns: Column[];
    items: any[];
    itemName: string;
    onClickItem: (scope: IScope, locals: any) => void;
    showConfiguration: boolean = false;
    sortColumn: Column;
    sortReverse: boolean = false;

    static $inject = [ '$scope' ];
    constructor(private $scope: IScope) {
        this.columns = [];
    }

    $onInit(): void {

    }

    addColumn(label: string, valueExpression: string): void {
        this.columns.push(new Column(label, valueExpression));
    }

    clickItem(item: any) {
        var locals = {};
        locals[this.itemName] = item;

        this.onClickItem(this.$scope, locals);
    }

    getItems(): any[] {
        if (this.sortColumn) {
            var self = this;

            return this.items.sort((item1: any, item2: any): number => {
                var value1 = this.getValue(item1, self.sortColumn.valueExpression);
                var value2 = this.getValue(item2, self.sortColumn.valueExpression);

                var result = 0;
                if (value1 < value2) {
                    result = -1;
                }
                else if (value1 > value2) {
                    result = 1;
                }

                return self.sortReverse ? -result : result;
            });
        }

        return this.items;
    }

    getValue(item: any, valueExpression: string) {
        var locals: any = {};
        // itemName comes from directive's link function
        locals[this.itemName] = item;

        return this.$scope.$eval(valueExpression, locals);
    }

    getVisibleColumns(): Column[] {
        return this.columns.filter((column: Column) => column.visible);
    }

    hideConfiguration(): void {
        this.showConfiguration = false;
    }

    sortOnColumn(column: Column): void {
        if (this.sortColumn === column) {
            // Reverse sort order if the column has already been sorted
            this.sortReverse = !this.sortReverse;
        }
        else {
            // Reset sort order to normal sort order
            this.sortReverse = false;
        }

        this.sortColumn = column;
    }

    toggleConfigurationVisibility(event: Event) {
        this.showConfiguration = !this.showConfiguration;

        event.stopImmediatePropagation();
    }

    reverseSort(): void {
        this.sortOnColumn(this.sortColumn);
    }
}
