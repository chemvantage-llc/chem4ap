export interface Question {
    id: number;
    type: string;
    prompt: string;
    choices: string[];
    scrambled: boolean;
    parameter: number;
    correctAnswer: string;
    studentAnswer: string;
    units: string;
}