import { model, Schema, type HydratedDocument, type InferSchemaType } from 'mongoose';

const userSchema = new Schema(
  {
    displayName: {
      type: String,
      required: true,
      trim: true,
      minlength: 1,
      maxlength: 50,
    },
  },
  { timestamps: true },
);

export type User = InferSchemaType<typeof userSchema>;
export type UserDocument = HydratedDocument<User>;

export const UserModel = model('User', userSchema);
