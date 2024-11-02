package ars.rockycube.entity


import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityQueryBuilder
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.FieldValueCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ExtendedFieldValueCondition extends FieldValueCondition {
    protected final static Logger logger = LoggerFactory.getLogger(ExtendedFieldValueCondition.class);

    protected ArrayList nestedFields = null

    public ExtendedFieldValueCondition(ConditionField field, ComparisonOperator operator, Object value){
        super(field, operator, value)
    }

    @Override
    public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) {
        super.makeSqlWhere(eqb, subMemberEd)

        // this is where we shall fix the condition in some special cases
        // 1. if nested (works for postgres, does not work for H2)
        if (!hasNestedFields) return

        // modify condition once set by the parent
        def lastConditionSql = this.sqlAppended
        def firstPos = eqb.sqlTopLevel.length() - lastConditionSql.length()
        def lastPos = firstPos + lastConditionSql.length()

        // calculate value
        def newCondition = eqb.efi.jsonFieldManipulator.formatNestedCondition(
                eqb.mainEntityDefinition,
                this.field,
                this.nestedFields,
                this.operator)
        // modify it
        eqb.sqlTopLevel.replace(firstPos, lastPos, newCondition)
    }

    public getHasNestedFields()
    {
        if (!this.nestedFields) return false
        return !this.nestedFields.empty
    }

    public setNestedFields(String fields)
    {
        if (!fields) return

        // re-set
        if (this.nestedFields == null) this.nestedFields = new ArrayList()
        if (fields.contains("."))
        {
            def fieldSplit = fields.split('.')
            fieldSplit.each {
                this.nestedFields.add(it)
            }
        }  else {
            this.nestedFields.add(fields)
        }
    }
}
